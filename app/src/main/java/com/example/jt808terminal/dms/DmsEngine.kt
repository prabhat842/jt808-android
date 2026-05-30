package com.example.jt808terminal.dms

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.jt808terminal.core.AppSettings
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Driver Monitoring System — two-stage pipeline.
 *
 * Stage 1 — MediaPipe FaceLandmarker v2 (blendshapes model):
 *   • 478 3D face landmarks for head-pose estimation
 *   • eyeBlinkLeft / eyeBlinkRight blend-shape scores → PERCLOS
 *   • jawOpen blend-shape score → yawn detection
 *
 * Stage 2 — Intel OMZ open-closed-eye-0001 (TFLite, 26 KB):
 *   • Dedicated binary eye-state classifier on 32×32 cropped eye patches
 *   • Overrides blend-shape eye decision when the two disagree (TFLite wins)
 *   • Gracefully absent: falls back to blend-shapes only if model file missing
 *
 * PERCLOS and alarm logic are unchanged from the previous implementation.
 */
class DmsEngine(
    private val context: Context,
    private val alarmState: DmsAlarmState,
) {
    private var faceLandmarker: FaceLandmarker? = null
    private var eyeClassifier: Interpreter? = null
    private var analysisExecutor: ExecutorService? = null
    private val perclos = PerclosTracker(windowMs = 60_000L)

    private var lastYawnMs = 0L
    private val yawnTimestamps = ArrayDeque<Long>()
    private var lastFaceSeenMs = 0L

    // Thresholds loaded from AppSettings on each engine start.
    private var eyeBlinkThreshold = 0.50f   // blend-shape score → closed
    private var jawOpenThreshold  = 0.60f   // blend-shape score → yawning
    private var perclosL1         = 0.35f
    private var perclosL2         = 0.50f
    private var headYawRatio      = 0.25f
    private var headPitchRatio    = 0.15f

    var onAlarmLevelChange: ((Int) -> Unit)? = null

    companion object {
        private const val TAG = "DmsEngine"
        private const val MODEL_LANDMARKS  = "face_landmarker_v2_with_blendshapes.task"
        private const val MODEL_EYE_TFLITE = "open_closed_eye.tflite"

        // Blend-shape category names (MediaPipe FaceLandmarker v2)
        private const val BS_BLINK_L = "eyeBlinkLeft"
        private const val BS_BLINK_R = "eyeBlinkRight"
        private const val BS_JAW     = "jawOpen"

        // EAR 6-point landmark indices (fallback when blend shapes unavailable)
        private val LEFT_EYE  = intArrayOf(362, 385, 387, 263, 373, 380)
        private val RIGHT_EYE = intArrayOf( 33, 160, 158, 133, 153, 144)

        // Head-pose reference landmarks
        private const val NOSE_TIP  = 4
        private const val FOREHEAD  = 10
        private const val CHIN      = 152
        private const val EYE_L_OUT = 33
        private const val EYE_R_OUT = 263

        private const val YAWN_COOLDOWN_MS    = 10_000L
        private const val YAWN_WINDOW_MS      = 600_000L
        private const val YAWN_COUNT_THRESH   = 3
        private const val NO_FACE_TIMEOUT_MS  = 10_000L
        private const val NO_FACE_SPEED_KPH   = 20f

        // TFLite model I/O — confirmed from onnx2tf conversion output:
        //   input:  [1, 32, 32, 3]  float32  NHWC BGR
        //   output: [1,  1,  1, 2]  float32  [closed_score, open_score]
        private const val EYE_SIZE = 32
    }

    // ── Initialisation ────────────────────────────────────────────────────────

    fun getImageAnalysis(): ImageAnalysis {
        val s = AppSettings(context)
        eyeBlinkThreshold = s.dmsEyeBlinkThreshold
        jawOpenThreshold  = s.dmsJawOpenThreshold
        perclosL1         = s.dmsPerclosL1
        perclosL2         = s.dmsPerclosL2
        headYawRatio      = s.dmsHeadYawRatio
        headPitchRatio    = s.dmsHeadPitchRatio
        Log.i(TAG, "Thresholds — blink=$eyeBlinkThreshold jaw=$jawOpenThreshold " +
                "PERCLOS=$perclosL1/$perclosL2 yaw=$headYawRatio pitch=$headPitchRatio")

        // Stage 1 — MediaPipe blendshapes landmarker
        val mpOptions = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_LANDMARKS).build())
            .setRunningMode(RunningMode.VIDEO)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(0.3f)
            .setMinFacePresenceConfidence(0.3f)
            .setMinTrackingConfidence(0.3f)
            .setOutputFaceBlendshapes(true)
            .setOutputFacialTransformationMatrixes(false)
            .build()
        faceLandmarker = FaceLandmarker.createFromOptions(context, mpOptions)
        Log.i(TAG, "FaceLandmarker v2 (blendshapes) loaded")

        // Stage 2 — TFLite eye state classifier (optional)
        try {
            val buf = loadModelBuffer(MODEL_EYE_TFLITE)
            eyeClassifier = Interpreter(buf, Interpreter.Options().apply { setNumThreads(2) })
            Log.i(TAG, "TFLite eye classifier loaded (${buf.capacity() / 1024} KB)")
        } catch (e: Exception) {
            Log.w(TAG, "TFLite eye model unavailable — blend shapes only: ${e.message}")
        }

        val executor = Executors.newSingleThreadExecutor()
        analysisExecutor = executor

        val analysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(640, 480))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(executor) { proxy -> processProxy(proxy) }
        return analysis
    }

    fun stop() {
        analysisExecutor?.shutdown(); analysisExecutor = null
        faceLandmarker?.close();     faceLandmarker = null
        eyeClassifier?.close();      eyeClassifier = null
        alarmState.behaviourFlags = 0; alarmState.fatigueDegree = 0
        alarmState.faceDetected = false; alarmState.alarmLevel = 0
        Log.i(TAG, "DmsEngine stopped")
    }

    // ── Frame processing ──────────────────────────────────────────────────────

    @SuppressLint("UnsafeOptInUsageError")
    private fun processProxy(proxy: ImageProxy) {
        val flm = faceLandmarker ?: run { proxy.close(); return }
        try {
            val raw = proxy.toBitmap()
            val deg = proxy.imageInfo.rotationDegrees
            val bmp = if (deg != 0) Bitmap.createBitmap(
                raw, 0, 0, raw.width, raw.height,
                Matrix().apply { postRotate(deg.toFloat()) }, false)
            else raw

            val result = flm.detectForVideo(BitmapImageBuilder(bmp).build(),
                SystemClock.elapsedRealtime())
            if (result.faceLandmarks().isEmpty())
                Log.d(TAG, "No face — ${bmp.width}×${bmp.height} rot=${deg}°")
            onResult(result, bmp)
        } catch (e: Exception) {
            Log.w(TAG, "processProxy error: ${e.message}")
        } finally {
            proxy.close()
        }
    }

    private fun onResult(result: FaceLandmarkerResult, bmp: Bitmap) {
        val now = System.currentTimeMillis()

        if (result.faceLandmarks().isEmpty()) {
            alarmState.faceDetected = false
            alarmState.eyesClosed = false; alarmState.isYawning = false
            alarmState.headDistracted = false
            if (lastFaceSeenMs > 0
                && (now - lastFaceSeenMs) > NO_FACE_TIMEOUT_MS
                && alarmState.currentSpeedKph > NO_FACE_SPEED_KPH) {
                val prev = alarmState.alarmLevel
                alarmState.behaviourFlags = alarmState.behaviourFlags or 0x01
                alarmState.fatigueDegree  = 100
                updateAlarmLevel(2, prev)
                Log.w(TAG, "No face ${(now-lastFaceSeenMs)/1000}s @ ${alarmState.currentSpeedKph}km/h")
            }
            return
        }

        val lm = result.faceLandmarks()[0]
        lastFaceSeenMs = now
        alarmState.faceDetected = true

        // ── Stage 1: Blend-shape eye & jaw scores ─────────────────────────
        val bs = result.faceBlendshapes()
        var eyesClosed: Boolean
        var isYawning: Boolean

        if (bs.isPresent && bs.get().isNotEmpty()) {
            val cats = bs.get()[0]
            val blinkL = cats.find { it.categoryName() == BS_BLINK_L }?.score() ?: 0f
            val blinkR = cats.find { it.categoryName() == BS_BLINK_R }?.score() ?: 0f
            val blinkAvg = (blinkL + blinkR) / 2f
            val jawOpen  = cats.find { it.categoryName() == BS_JAW }?.score() ?: 0f

            eyesClosed = blinkAvg > eyeBlinkThreshold
            isYawning  = jawOpen  > jawOpenThreshold

            Log.v(TAG, "BS  blink=%.2f jaw=%.2f".format(blinkAvg, jawOpen))

            // ── Stage 2: TFLite eye classifier override ───────────────────
            val clf = eyeClassifier
            if (clf != null) {
                val tflClosed = inferEyeState(clf, bmp, lm)
                if (tflClosed != null && tflClosed != eyesClosed) {
                    Log.d(TAG, "TFLite overrides blend: eyesClosed $eyesClosed→$tflClosed")
                    eyesClosed = tflClosed
                }
            }
        } else {
            // Fallback EAR (blend shapes absent — model mismatch or older device)
            val earL = ear(lm, LEFT_EYE); val earR = ear(lm, RIGHT_EYE)
            eyesClosed = (earL + earR) / 2f < 0.20f
            isYawning  = mar(lm) > 0.50f
            Log.v(TAG, "EAR fallback earL=%.2f earR=%.2f".format(earL, earR))
        }

        perclos.record(eyesClosed, now)

        if (isYawning && (now - lastYawnMs) > YAWN_COOLDOWN_MS) {
            lastYawnMs = now; recordYawn(now)
            Log.i(TAG, "Yawn count=${yawnCount()}")
        }

        val headDistracted = headDistracted(lm)
        val perclosVal = perclos.perclos()
        val fatigueDeg = (perclosVal * 100).toInt().coerceIn(0, 100)
        val newLevel = when {
            perclosVal >= perclosL2           -> 2
            perclosVal >= perclosL1           -> 1
            yawnCount() >= YAWN_COUNT_THRESH  -> 1
            else                              -> 0
        }

        val prev = alarmState.alarmLevel
        alarmState.behaviourFlags  = if (newLevel > 0) alarmState.behaviourFlags or 0x01 else 0
        alarmState.fatigueDegree   = fatigueDeg
        alarmState.eyesClosed      = eyesClosed
        alarmState.isYawning       = isYawning
        alarmState.yawnCount       = yawnCount()
        alarmState.headDistracted  = headDistracted
        updateAlarmLevel(newLevel, prev)

        Log.v(TAG, "eyes=$eyesClosed yawn=$isYawning PERCLOS=${fatigueDeg}%% level=$newLevel")
    }

    // ── TFLite eye inference ──────────────────────────────────────────────────

    /**
     * Crops both eyes, runs TFLite classifier on each, returns true if either eye is closed.
     * Returns null if crops are too small or inference fails.
     */
    private fun inferEyeState(clf: Interpreter, bmp: Bitmap, lm: List<NormalizedLandmark>): Boolean? {
        val leftClosed  = classifyEye(clf, bmp, lm, LEFT_EYE)
        val rightClosed = classifyEye(clf, bmp, lm, RIGHT_EYE)
        return if (leftClosed != null || rightClosed != null)
            (leftClosed ?: false) || (rightClosed ?: false)
        else null
    }

    private fun classifyEye(clf: Interpreter, bmp: Bitmap,
                            lm: List<NormalizedLandmark>, idx: IntArray): Boolean? {
        val crop = cropEye(bmp, lm, idx) ?: return null
        // Input: [1, 32, 32, 3]  float32  NHWC  BGR 0-1
        val input = Array(1) { Array(EYE_SIZE) { Array(EYE_SIZE) { FloatArray(3) } } }
        for (y in 0 until EYE_SIZE) for (x in 0 until EYE_SIZE) {
            val px = crop.getPixel(x, y)
            input[0][y][x][0] = (px and 0xFF) / 255f          // B
            input[0][y][x][1] = (px shr 8 and 0xFF) / 255f    // G
            input[0][y][x][2] = (px shr 16 and 0xFF) / 255f   // R (original model is BGR)
        }
        // Output: [1, 1, 1, 2]  → [closed_score, open_score]
        val output = Array(1) { Array(1) { Array(1) { FloatArray(2) } } }
        return try {
            clf.run(input, output)
            val closed = output[0][0][0][0]; val open = output[0][0][0][1]
            Log.v(TAG, "TFLite eye — closed=%.3f open=%.3f".format(closed, open))
            closed > open
        } catch (e: Exception) {
            Log.w(TAG, "TFLite inference error: ${e.message}"); null
        }
    }

    private fun cropEye(bmp: Bitmap, lm: List<NormalizedLandmark>, idx: IntArray): Bitmap? {
        val xs = idx.map { lm[it].x() * bmp.width  }
        val ys = idx.map { lm[it].y() * bmp.height }
        val cx = xs.average().toFloat(); val cy = ys.average().toFloat()
        val half = ((xs.max() - xs.min()) * 1.2f).toInt().coerceAtLeast(8)
        val l = (cx - half).toInt().coerceIn(0, bmp.width  - 1)
        val t = (cy - half).toInt().coerceIn(0, bmp.height - 1)
        val r = (cx + half).toInt().coerceIn(l + 1, bmp.width)
        val b = (cy + half).toInt().coerceIn(t + 1, bmp.height)
        if (r - l < 4 || b - t < 4) return null
        val crop = Bitmap.createBitmap(bmp, l, t, r - l, b - t)
        return Bitmap.createScaledBitmap(crop, EYE_SIZE, EYE_SIZE, true)
    }

    // ── Geometry helpers ──────────────────────────────────────────────────────

    private fun ear(lm: List<NormalizedLandmark>, idx: IntArray): Float {
        val a = dist(lm[idx[1]], lm[idx[5]]); val b = dist(lm[idx[2]], lm[idx[4]])
        val c = dist(lm[idx[0]], lm[idx[3]])
        return if (c < 1e-6f) 0f else (a + b) / (2f * c)
    }

    private fun mar(lm: List<NormalizedLandmark>): Float {
        val h = dist(lm[13], lm[14]); val w = dist(lm[61], lm[291])
        return if (w < 1e-6f) 0f else h / w
    }

    private fun headDistracted(lm: List<NormalizedLandmark>): Boolean {
        val noseX   = lm[NOSE_TIP].x()
        val eyeMidX = (lm[EYE_L_OUT].x() + lm[EYE_R_OUT].x()) / 2f
        val halfSpan = abs(lm[EYE_R_OUT].x() - lm[EYE_L_OUT].x()) / 2f
        val yawRatio = if (halfSpan > 0.01f) abs(noseX - eyeMidX) / halfSpan else 0f

        val noseTipY = lm[NOSE_TIP].y()
        val faceH    = lm[CHIN].y() - lm[FOREHEAD].y()
        val pitchRatio = if (faceH > 0.01f) abs((noseTipY - lm[FOREHEAD].y()) / faceH - 0.55f) else 0f

        return yawRatio > headYawRatio || pitchRatio > headPitchRatio
    }

    private fun dist(a: NormalizedLandmark, b: NormalizedLandmark): Float {
        val dx = a.x() - b.x(); val dy = a.y() - b.y()
        return sqrt(dx * dx + dy * dy)
    }

    // ── TFLite model loading ──────────────────────────────────────────────────

    private fun loadModelBuffer(assetName: String): MappedByteBuffer {
        val afd = context.assets.openFd(assetName)
        return FileInputStream(afd.fileDescriptor).channel.map(
            FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
    }

    // ── Alarm helpers ─────────────────────────────────────────────────────────

    private fun updateAlarmLevel(newLevel: Int, prevLevel: Int) {
        alarmState.alarmLevel = newLevel
        if (newLevel >= 2 && prevLevel < 2) onAlarmLevelChange?.invoke(newLevel)
    }

    private fun recordYawn(now: Long) {
        yawnTimestamps.addLast(now)
        val cutoff = now - YAWN_WINDOW_MS
        while (yawnTimestamps.isNotEmpty() && yawnTimestamps.first() < cutoff)
            yawnTimestamps.removeFirst()
    }

    private fun yawnCount() = yawnTimestamps.size
}
