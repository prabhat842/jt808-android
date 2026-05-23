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
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark

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

    // HandlerThread-affine state (only touched from the DMS HandlerThread)
    private var lastYawnMs = 0L
    private val yawnTimestamps = ArrayDeque<Long>()  // 10-min yawn count window
    private var lastFaceSeenMs = 0L                  // for no-face timeout

    /** Invoked when alarm level first reaches 2 (danger). Triggers immediate 0x0200. */
    var onAlarmLevelChange: ((Int) -> Unit)? = null

    companion object {
        private const val TAG = "DmsEngine"
        // PERCLOS thresholds — spec §5.2: level 1 warning / level 2 danger
        private const val PERCLOS_L1 = 0.35f   // 35% → level 1 warning
        private const val PERCLOS_L2 = 0.50f   // 50% → level 2 danger + auto 0x9101
        // Eye-open probability < 0.3 → consider eyes closed (ML Kit 0.0-1.0 range)
        private const val EYE_CLOSED_PROB = 0.3f
        // Landmark proxy: (noseBaseY → bottomMouthY) / faceHeight > 0.28 → yawning
        private const val YAWN_THRESHOLD = 0.28f
        // Suppress repeated yawn detection for 10 s
        private const val YAWN_COOLDOWN_MS = 10_000L
        // 3 yawns in 10-minute window → additional fatigue signal — spec §5.3
        private const val YAWN_WINDOW_MS = 600_000L
        private const val YAWN_COUNT_THRESHOLD = 3
        // No face visible for 10 s while speed > 20 km/h → danger alarm — spec §5.2
        private const val NO_FACE_TIMEOUT_MS = 10_000L
        private const val NO_FACE_SPEED_KPH = 20f
    }

    /**
     * Builds and returns the CameraX ImageAnalysis use case.
     * The HandlerThread and detector are started here; frames won't flow until
     * the use case is bound to a camera lifecycle by TerminalService.bindCamera().
     * Call once; reuse the returned instance across streaming sessions.
     */
    fun getImageAnalysis(): ImageAnalysis {
        handlerThread.start()

        // FAST mode: single JNI thread vs ACCURATE's 6-thread pool.
        // CONTOUR_MODE_NONE + LANDMARK_MODE_ALL: landmarks include NOSE_BASE + BOTTOM_MOUTH
        // for yawn detection — cheaper than full contours, sufficient accuracy.
        detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                .setMinFaceSize(0.15f)
                .build()
        )

        val executor = java.util.concurrent.Executor { cmd ->
            Handler(handlerThread.looper).post(cmd)
        }

        val analysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(320, 240))
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
        alarmState.alarmLevel = 0
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
        val now = System.currentTimeMillis()

        if (faces.isEmpty()) {
            alarmState.faceDetected = false
            // Don't update PERCLOS — driver may be briefly looking away.
            // Check no-face timeout: if face absent for > 10 s while driving → danger alarm.
            if (lastFaceSeenMs > 0
                && (now - lastFaceSeenMs) > NO_FACE_TIMEOUT_MS
                && alarmState.currentSpeedKph > NO_FACE_SPEED_KPH
            ) {
                val prevLevel = alarmState.alarmLevel
                alarmState.behaviourFlags = alarmState.behaviourFlags or 0x01
                alarmState.fatigueDegree = 100
                updateAlarmLevel(2, prevLevel)
                Log.w(TAG, "No face for ${(now - lastFaceSeenMs) / 1000}s at ${alarmState.currentSpeedKph}km/h — danger alarm")
            }
            return
        }

        val face = faces[0]   // primary face (largest area, first in list)
        lastFaceSeenMs = now
        alarmState.faceDetected = true

        // ----- PERCLOS (eye closure) ----------------------------------------
        val leftP  = face.leftEyeOpenProbability
        val rightP = face.rightEyeOpenProbability
        val eyesClosed = when {
            leftP != null && rightP != null -> ((leftP + rightP) / 2f) < EYE_CLOSED_PROB
            leftP  != null -> leftP  < EYE_CLOSED_PROB
            rightP != null -> rightP < EYE_CLOSED_PROB
            else -> false   // no eye data — skip sample
        }
        perclos.record(eyesClosed, now)

        val perclosValue = perclos.perclos()
        val fatigueDeg   = (perclosValue * 100).toInt().coerceIn(0, 100)

        // ----- Yawning (mouth contour) — spec §5.3 --------------------------
        val isYawning = detectYawning(face)
        if (isYawning && (now - lastYawnMs) > YAWN_COOLDOWN_MS) {
            lastYawnMs = now
            recordYawn(now)
            Log.i(TAG, "Yawn detected — count in 10 min: ${yawnCount()}")
        }

        // ----- Alarm level — spec §5.2 --------------------------------------
        // Yawn count ≥ 3 in 10 min contributes to level 1 fatigue signal.
        val yawnFatigue = yawnCount() >= YAWN_COUNT_THRESHOLD
        val newLevel = when {
            perclosValue >= PERCLOS_L2 -> 2          // > 50% → danger
            perclosValue >= PERCLOS_L1 -> 1          // 35-50% → warning
            yawnFatigue               -> 1           // yawn count threshold
            else                      -> 0
        }

        // ----- Update shared alarm state ------------------------------------
        var flags = 0
        if (newLevel > 0) flags = flags or 0x01  // bit 0: fatigue
        // bit 1 (phone) / bit 2 (smoking) require custom TFLite model

        val prevLevel = alarmState.alarmLevel
        alarmState.behaviourFlags = flags
        alarmState.fatigueDegree  = fatigueDeg
        updateAlarmLevel(newLevel, prevLevel)

        Log.v(TAG, "PERCLOS=${fatigueDeg}% level=$newLevel yawns=${yawnCount()} eyesClosed=$eyesClosed")
    }

    private fun updateAlarmLevel(newLevel: Int, prevLevel: Int) {
        alarmState.alarmLevel = newLevel
        // Fire callback only when first reaching danger (level 2) — triggers immediate 0x0200.
        if (newLevel >= 2 && prevLevel < 2) {
            onAlarmLevelChange?.invoke(newLevel)
        }
    }

    private fun recordYawn(now: Long) {
        yawnTimestamps.addLast(now)
        val cutoff = now - YAWN_WINDOW_MS
        while (yawnTimestamps.isNotEmpty() && yawnTimestamps.first() < cutoff) {
            yawnTimestamps.removeFirst()
        }
    }

    private fun yawnCount() = yawnTimestamps.size

    /**
     * Estimates mouth openness from NOSE_BASE and BOTTOM_MOUTH landmarks.
     * Uses (noseY → mouthBottomY) span relative to the face bounding-box height.
     * LANDMARK_MODE_ALL must be set; contours are not needed.
     *
     * Vertical lip gap is not directly available without contours, so we proxy it as:
     *   gap ≈ bottomMouthY − noseBaseY   (increases as mouth opens)
     * Calibrated threshold at 0.28 × faceHeight (equivalent to contour ratio of 0.20).
     */
    private fun detectYawning(face: Face): Boolean {
        val nose   = face.getLandmark(FaceLandmark.NOSE_BASE)?.position ?: return false
        val bottom = face.getLandmark(FaceLandmark.BOTTOM_MOUTH)?.position ?: return false
        val faceH  = face.boundingBox.height().toFloat()
        if (faceH <= 0f) return false
        val gap = bottom.y - nose.y   // positive when mouth hangs open
        return (gap / faceH) > YAWN_THRESHOLD
    }
}
