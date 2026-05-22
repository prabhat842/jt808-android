package com.example.jt808terminal.dms

import android.annotation.SuppressLint
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions

/**
 * Driver Monitoring System engine.
 *
 * Wires a CameraX ImageAnalysis use case to ML Kit Face Detection on a dedicated HandlerThread
 * so ML inference never touches the main/UI/camera thread.
 *
 * Detects:
 *   PERCLOS fatigue — 60-second rolling window; flags bit 0 of behaviourFlags when ≥ 35%
 *   Yawning         — mouth open ratio > 0.20 of face height (suppressed for 10 s after trigger)
 *
 * Phone use and smoking require a custom TFLite model (not included in Phase 4).
 * Seatbelt detection requires a secondary cabin-view camera (not included in Phase 4).
 *
 * Alarm bit wiring — JT808-2013 Table 24:
 *   Bit 14 = Fatigue driving warning (set when PERCLOS ≥ FATIGUE_THRESHOLD)
 *
 * Additional info — JT1078-2016 extension (ID 0x18):
 *   Behaviour flags WORD + fatigue degree BYTE
 */
class DmsEngine(private val alarmState: DmsAlarmState) {

    private val handlerThread = HandlerThread("dms-analysis")
    private var detector: FaceDetector? = null
    private val perclos = PerclosTracker(windowMs = 60_000L)
    private var lastYawnMs = 0L

    companion object {
        private const val TAG = "DmsEngine"
        // JT808-2013 Table 24: bit 14 = fatigue driving warning
        // PERCLOS threshold: 35% eye-closed fraction triggers fatigue — NHTSA guideline
        private const val FATIGUE_THRESHOLD = 0.35f
        // Eye-open probability < 0.3 → consider eyes closed (ML Kit range 0.0-1.0)
        private const val EYE_CLOSED_PROB = 0.3f
        // Mouth open (lip gap / face height) > 0.20 → yawning
        private const val YAWN_THRESHOLD = 0.20f
        // Suppress repeated yawn events for 10 seconds
        private const val YAWN_COOLDOWN_MS = 10_000L
    }

    /**
     * Builds and returns the CameraX ImageAnalysis use case.
     * The HandlerThread and detector are started here; frames won't flow until
     * the use case is bound to a camera lifecycle by VideoEncoder.bindCamera().
     * Call once; reuse the returned instance across streaming sessions.
     */
    fun getImageAnalysis(): ImageAnalysis {
        handlerThread.start()

        detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                .setMinFaceSize(0.15f)
                .build()
        )

        val executor = java.util.concurrent.Executor { cmd ->
            Handler(handlerThread.looper).post(cmd)
        }

        val analysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(640, 480))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        analysis.setAnalyzer(executor) { proxy -> processProxy(proxy) }
        Log.i(TAG, "ImageAnalysis use case ready")
        return analysis
    }

    fun stop() {
        detector?.close()
        detector = null
        handlerThread.quitSafely()
        alarmState.behaviourFlags = 0
        alarmState.fatigueDegree = 0
        alarmState.faceDetected = false
        Log.i(TAG, "DmsEngine stopped")
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun processProxy(proxy: ImageProxy) {
        val mediaImage = proxy.image
        if (mediaImage == null) { proxy.close(); return }

        val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
        val det = detector
        if (det == null) { proxy.close(); return }

        val handlerExecutor = java.util.concurrent.Executor { cmd ->
            Handler(handlerThread.looper).post(cmd)
        }

        det.process(image)
            .addOnSuccessListener(handlerExecutor) { faces -> onFaces(faces) }
            .addOnFailureListener(handlerExecutor) { e ->
                Log.w(TAG, "Face detection failed: ${e.message}")
            }
            .addOnCompleteListener { proxy.close() }  // always release — any thread is fine
    }

    // Runs on the DMS HandlerThread.
    private fun onFaces(faces: List<Face>) {
        if (faces.isEmpty()) {
            alarmState.faceDetected = false
            // Don't update PERCLOS when face not visible — driver may be briefly looking away
            return
        }

        val face = faces[0]   // primary face (largest area)
        val now = System.currentTimeMillis()
        alarmState.faceDetected = true

        // ----- PERCLOS (eye closure) ----------------------------------------
        val leftP  = face.leftEyeOpenProbability
        val rightP = face.rightEyeOpenProbability
        val eyesClosed = when {
            leftP != null && rightP != null -> ((leftP + rightP) / 2f) < EYE_CLOSED_PROB
            leftP  != null -> leftP  < EYE_CLOSED_PROB
            rightP != null -> rightP < EYE_CLOSED_PROB
            else -> false   // no eye data — skip this sample
        }
        perclos.record(eyesClosed, now)

        val perclosValue = perclos.perclos()
        val isFatigued = perclosValue >= FATIGUE_THRESHOLD
        val fatigueDeg  = (perclosValue * 100).toInt().coerceIn(0, 100)

        // ----- Yawning (mouth contour) ---------------------------------------
        val isYawning = detectYawning(face)
        if (isYawning && (now - lastYawnMs) > YAWN_COOLDOWN_MS) {
            lastYawnMs = now
            Log.i(TAG, "Yawn detected")
        }

        // ----- Update shared alarm state ------------------------------------
        var flags = 0
        if (isFatigued) flags = flags or 0x01   // bit 0: fatigue — used in 0x18 TLV
        // bit 1 (phone use) and bit 2 (smoking) require custom TFLite model — Phase 4 stub

        alarmState.behaviourFlags = flags
        alarmState.fatigueDegree  = fatigueDeg

        Log.v(TAG, "PERCLOS=${(perclosValue * 100).toInt()}% fatigue=$isFatigued yawn=$isYawning")
    }

    /**
     * Estimates mouth openness from UPPER_LIP_BOTTOM and LOWER_LIP_TOP contours.
     * Ratio = vertical lip gap / face bounding-box height.
     * Contours available only when CONTOUR_MODE_ALL is set in detector options.
     */
    private fun detectYawning(face: Face): Boolean {
        val upperPts = face.getContour(FaceContour.UPPER_LIP_BOTTOM)?.points
        val lowerPts = face.getContour(FaceContour.LOWER_LIP_TOP)?.points
        if (upperPts.isNullOrEmpty() || lowerPts.isNullOrEmpty()) return false

        val upperY = upperPts.map { it.y }.average().toFloat()
        val lowerY = lowerPts.map { it.y }.average().toFloat()
        val gap   = lowerY - upperY   // positive when mouth is open (y increases downward)
        val faceH = face.boundingBox.height().toFloat()

        return faceH > 0f && (gap / faceH) > YAWN_THRESHOLD
    }
}
