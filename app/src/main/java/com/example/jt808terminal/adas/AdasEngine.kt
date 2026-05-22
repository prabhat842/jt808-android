package com.example.jt808terminal.adas

import android.annotation.SuppressLint
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.jt808terminal.bsd.BsdEngine
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import java.util.concurrent.Executor

/**
 * Advanced Driver Assistance System engine — Phase 5.
 *
 * Wires a CameraX ImageAnalysis use case (back camera, road-facing) to ML Kit Object Detection
 * on a dedicated HandlerThread so inference never touches the main/UI/camera thread.
 *
 * BSD co-analysis: an optional [BsdEngine] receives the same DetectedObject list on the
 * same HandlerThread, so both ADAS and BSD share a single back-camera ImageAnalysis use case.
 *
 * Detection logic (no custom TFLite model — all heuristic-based on bounding boxes):
 *
 *   FCW (Forward Collision Warning):
 *     Any detected object whose bounding-box area > FCW_AREA_THRESHOLD AND whose horizontal
 *     centre falls inside the middle FCW_CENTER_ZONE of the frame is considered a vehicle/obstacle
 *     ahead.  Alarm → JT808-2013 Table 24 bit 29 (Collision warning).
 *
 *   Pedestrian detection:
 *     Object with aspect ratio (height/width) > PEDESTRIAN_ASPECT_RATIO AND area > PEDESTRIAN_MIN_AREA
 *     is classified as person-shaped.  Alarm → Table 24 bit 3 (Risk warning).
 *
 *   LDW (Lane Departure Warning):
 *     A significant object (area > LDW_EDGE_AREA_THRESHOLD) whose centre-x is in the outermost
 *     LDW_EDGE_ZONE fraction of the frame suggests the vehicle is drifting toward a lane boundary.
 *     Alarm → Table 24 bit 3 (Risk warning, shared with pedestrian).
 *
 *   Speed warning / alarm:
 *     Pure GPS-based: reads [AdasAlarmState.currentSpeedKph] written by LocationReporter.
 *     Warning → Table 24 bit 13 (Over speed warning) at OVERSPEED_WARNING_KPH.
 *     Alarm   → Table 24 bit 1  (Over speed alarm)   at OVERSPEED_ALARM_KPH.
 */
class AdasEngine(
    private val alarmState: AdasAlarmState,
    private val bsdEngine: BsdEngine? = null,
) {
    private val handlerThread = HandlerThread("adas-analysis")
    private var detector: ObjectDetector? = null

    @Volatile private var frameWidth = 0
    @Volatile private var frameHeight = 0

    /** Invoked on first frame where FCW transitions from inactive to active. Triggers immediate 0x0200. */
    var onCollisionWarning: (() -> Unit)? = null

    companion object {
        private const val TAG = "AdasEngine"

        // FCW: object area > 20% of frame AND horizontal centre within middle 40% of frame.
        private const val FCW_AREA_THRESHOLD  = 0.20f
        private const val FCW_CENTER_ZONE     = 0.40f  // fraction of frame width

        // Pedestrian: aspect ratio height/width > 1.5 AND area > 3% of frame.
        private const val PEDESTRIAN_ASPECT_RATIO = 1.5f
        private const val PEDESTRIAN_MIN_AREA      = 0.03f

        // LDW: object in outermost 20% of frame width AND area > 10% of frame.
        private const val LDW_EDGE_ZONE           = 0.20f
        private const val LDW_EDGE_AREA_THRESHOLD = 0.10f

        // Speed thresholds — configurable via 0x8103 param 0x0055/0x0056 in Phase 8.
        private const val OVERSPEED_WARNING_KPH = 80f
        private const val OVERSPEED_ALARM_KPH   = 100f
    }

    /**
     * Builds and returns the CameraX ImageAnalysis use case for the back camera.
     * Starts the HandlerThread and the ML Kit ObjectDetector.
     * Call once; reuse the returned instance across service sessions.
     */
    fun getImageAnalysis(): ImageAnalysis {
        handlerThread.start()

        // STREAM_MODE for continuous road-scene analysis; multiple objects enabled so
        // FCW, pedestrian, and LDW detections can occur simultaneously in one frame.
        // PERFORMANCE_MODE_FAST keeps CPU load manageable alongside DMS on the front camera.
        detector = ObjectDetection.getClient(
            ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
                .enableMultipleObjects()
                .enableClassification()
                .setMaxPerObjectLabelCount(1)
                .build()
        )

        val executor = Executor { cmd -> Handler(handlerThread.looper).post(cmd) }

        val analysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(640, 480))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        analysis.setAnalyzer(executor) { proxy -> processProxy(proxy) }
        Log.i(TAG, "ADAS ImageAnalysis ready (back camera)")
        return analysis
    }

    fun stop() {
        detector?.close()
        detector = null
        handlerThread.quitSafely()
        alarmState.fcwActive       = false
        alarmState.pedestrianRisk  = false
        alarmState.ldwActive       = false
        alarmState.overSpeedWarning = false
        alarmState.overSpeedAlarm  = false
        bsdEngine?.reset()
        Log.i(TAG, "AdasEngine stopped")
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun processProxy(proxy: ImageProxy) {
        val mediaImage = proxy.image
        if (mediaImage == null) { proxy.close(); return }

        if (frameWidth == 0) {
            frameWidth  = proxy.width
            frameHeight = proxy.height
        }

        val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
        val det   = detector
        if (det == null) { proxy.close(); return }

        val executor = Executor { cmd -> Handler(handlerThread.looper).post(cmd) }

        det.process(image)
            .addOnSuccessListener(executor) { objects -> onObjects(objects) }
            .addOnFailureListener(executor) { e -> Log.w(TAG, "Object detection error: ${e.message}") }
            .addOnCompleteListener { proxy.close() }
    }

    // Runs on the ADAS HandlerThread.
    private fun onObjects(objects: List<DetectedObject>) {
        val w = frameWidth
        val h = frameHeight
        if (w == 0 || h == 0) return

        val frameArea = w * h
        val centerLeft  = w * (0.5f - FCW_CENTER_ZONE / 2f)
        val centerRight = w * (0.5f + FCW_CENTER_ZONE / 2f)
        val edgeLeft    = w * LDW_EDGE_ZONE
        val edgeRight   = w * (1f - LDW_EDGE_ZONE)

        var fcw        = false
        var pedestrian = false
        var ldw        = false

        for (obj in objects) {
            val bbox      = obj.boundingBox
            val objArea   = bbox.width() * bbox.height()
            val areaRatio = objArea.toFloat() / frameArea
            val cx        = bbox.centerX().toFloat()

            // FCW: large obstacle ahead in the centre lane
            if (areaRatio > FCW_AREA_THRESHOLD && cx in centerLeft..centerRight) {
                fcw = true
            }

            // Pedestrian: tall narrow silhouette anywhere in the road scene
            val aspect = bbox.height().toFloat() / bbox.width().coerceAtLeast(1)
            if (aspect > PEDESTRIAN_ASPECT_RATIO && areaRatio > PEDESTRIAN_MIN_AREA) {
                pedestrian = true
            }

            // LDW: large object encroaching on the frame edge (vehicle drifting to lane boundary)
            if (areaRatio > LDW_EDGE_AREA_THRESHOLD && (cx < edgeLeft || cx > edgeRight)) {
                ldw = true
            }
        }

        // Speed-based alarms — purely GPS, no vision needed.
        val speed         = alarmState.currentSpeedKph
        val overSpeedWarn = speed >= OVERSPEED_WARNING_KPH
        val overSpeedAlarm = speed >= OVERSPEED_ALARM_KPH

        val prevFcw = alarmState.fcwActive
        alarmState.fcwActive        = fcw
        alarmState.pedestrianRisk   = pedestrian
        alarmState.ldwActive        = ldw
        alarmState.overSpeedWarning = overSpeedWarn
        alarmState.overSpeedAlarm   = overSpeedAlarm

        // Fire callback only on the leading edge of FCW (inactive → active).
        if (fcw && !prevFcw) onCollisionWarning?.invoke()

        Log.v(TAG, "ADAS fcw=$fcw ped=$pedestrian ldw=$ldw speed=${"%.1f".format(speed)}km/h objects=${objects.size}")

        // BSD co-analysis on the same detected objects (same HandlerThread call).
        bsdEngine?.analyzeObjects(objects, w, h)
    }
}
