package com.example.jt808terminal.jt1078

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.atomic.AtomicLong

/**
 * H.264 video encoder using MediaCodec in Surface mode.
 *
 * Architecture (spec §4.3):
 *   CameraX Preview → inputSurface → MediaCodec H264 encoder → JT1078 packets → RtvsConnection
 *
 * SPS/PPS handling (spec §4.3 critical):
 *   MediaCodec emits SPS+PPS as a BUFFER_FLAG_CODEC_CONFIG buffer before the first key frame.
 *   We save that buffer and prepend it to the first IDR (key frame) payload, as a single
 *   JT1078 body — per spec: "SPS/PPS must be prepended to first I-frame as a single JT1078 body".
 *   SPS+PPS are NOT sent again on subsequent key frames (encoder reinjects them inline).
 *
 * Subpacket splitting (spec §4.3 critical):
 *   NAL units > 950 bytes are split via Jt1078Framer into FIRST/MIDDLE/LAST sub-packets.
 *
 * Default encoding: 720p @ 25fps, 1 Mbps CBR, keyframe interval 2s (spec §4.3).
 * Configurable via 0x8103 parameter 0x0075 / 0x0077 (Phase 2 stub, wired up in Phase 8).
 *
 * Channel mapping (spec §4.3): channel 1 = front camera (DMS / road-facing).
 */
class VideoEncoder(
    private val context: Context,
    private val phoneNumber: String,
    private val channel: Int,
    private val rtvsConnection: RtvsConnection,
    widthPx: Int = 1280,
    heightPx: Int = 720,
    frameRate: Int = 25,
    bitrateBps: Int = 1_000_000,
    keyFrameIntervalSec: Int = 2,
) {
    private val width = widthPx
    private val height = heightPx

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null

    // SPS+PPS captured from CODEC_CONFIG output buffer, prepended to first I-frame
    @Volatile private var spsPpsBuffer: ByteArray? = null
    @Volatile private var spsPpsSent = false

    private val seqNum = AtomicLong(0)
    private var lastIFrameMs = 0L
    private var lastFrameMs = 0L

    @Volatile private var encoderRunning = false
    private var outputThread: Thread? = null

    init {
        setupCodec(widthPx, heightPx, frameRate, bitrateBps, keyFrameIntervalSec)
    }

    // -- Setup ---------------------------------------------------------------

    private fun setupCodec(w: Int, h: Int, fps: Int, bps: Int, keyInterval: Int) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            // Surface input — CameraX renders frames directly into the encoder
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bps)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            // Keyframe every 2 seconds — JT/T 1078-2016 §5.3.1 Table 2
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyInterval)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec!!.createInputSurface()
        Log.i(TAG, "Encoder configured: ${w}x${h} ${fps}fps ${bps / 1000}kbps CBR keyInt=${keyInterval}s")
    }

    // -- Start / Stop --------------------------------------------------------

    /**
     * Starts the encoder and binds the camera.
     * Must be called on the main thread (CameraX requirement).
     * Pass extra CameraX use cases (e.g. DmsEngine.getImageAnalysis()) to bind alongside
     * the Preview so all use cases share one camera session.
     */
    @SuppressLint("UnsafeOptInUsageError")
    fun start(lifecycleOwner: LifecycleOwner, vararg extraUseCases: UseCase) {
        codec!!.start()
        startOutputLoop()
        bindCamera(lifecycleOwner, extraUseCases)
        Log.i(TAG, "VideoEncoder started ch=$channel")
    }

    fun stop() {
        encoderRunning = false
        outputThread?.join(2_000L)
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        inputSurface?.release()
        inputSurface = null
        spsPpsBuffer = null
        spsPpsSent = false
        Log.i(TAG, "VideoEncoder stopped ch=$channel")
    }

    /** Returns the Surface that CameraX must render into. */
    fun getInputSurface(): Surface = inputSurface ?: error("Encoder not initialized")

    // -- Camera binding (CameraX) -------------------------------------------

    // Binds Preview (encoder surface) plus any extra use cases in one camera session.
    // unbindAll() is intentional: ensures a clean bind even if a prior session is still live.
    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCamera(owner: LifecycleOwner, extra: Array<out UseCase>) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder()
                    .setTargetResolution(Size(width, height))
                    .build()
                preview.setSurfaceProvider { request ->
                    val surface = inputSurface ?: return@setSurfaceProvider
                    request.provideSurface(surface, ContextCompat.getMainExecutor(context)) {}
                }
                // Channel 1 = front/driver-facing camera — spec §4.3
                val selector = CameraSelector.DEFAULT_FRONT_CAMERA
                provider.unbindAll()
                provider.bindToLifecycle(owner, selector, preview, *extra)
                Log.i(TAG, "Camera bound (front) + ${extra.size} extra use case(s)")
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    // -- Encoder output loop ------------------------------------------------

    private fun startOutputLoop() {
        encoderRunning = true
        outputThread = Thread({
            val info = MediaCodec.BufferInfo()
            while (encoderRunning) {
                try {
                    val idx = codec?.dequeueOutputBuffer(info, 10_000L) ?: break
                    when {
                        idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            Log.d(TAG, "Output format changed")
                        }
                        idx >= 0 -> {
                            processOutputBuffer(idx, info)
                        }
                    }
                } catch (e: Exception) {
                    if (encoderRunning) Log.w(TAG, "Encoder output error: ${e.message}")
                }
            }
        }, "jt1078-encoder-ch$channel")
        outputThread!!.start()
    }

    private fun processOutputBuffer(idx: Int, info: MediaCodec.BufferInfo) {
        val codec = codec ?: return
        val buf = codec.getOutputBuffer(idx) ?: run { codec.releaseOutputBuffer(idx, false); return }

        val isConfig    = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
        val isKeyFrame  = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
        val nowMs = System.currentTimeMillis()

        if (info.size > 0) {
            val raw = ByteArray(info.size)
            buf.position(info.offset)
            buf.get(raw)

            if (isConfig) {
                // Save SPS+PPS — will be prepended to first I-frame — spec §4.3
                spsPpsBuffer = raw
                Log.d(TAG, "SPS+PPS captured (${raw.size}B)")
            } else {
                val payload = if (isKeyFrame && !spsPpsSent) {
                    // Prepend SPS+PPS to first IDR — spec §4.3 critical constraint
                    val sps = spsPpsBuffer
                    spsPpsSent = true
                    if (sps != null) sps + raw else raw
                } else {
                    raw
                }

                val prevI = if (lastIFrameMs > 0) (nowMs - lastIFrameMs).toInt() else 0
                val prevF = if (lastFrameMs > 0) (nowMs - lastFrameMs).toInt() else 0

                val packets = Jt1078Framer.buildVideoPackets(
                    phoneNumber = phoneNumber,
                    channel = channel,
                    seqBase = seqNum.get(),
                    payload = payload,
                    isKeyFrame = isKeyFrame,
                    timestampMs = nowMs,
                    prevIFrameIntervalMs = prevI,
                    prevFrameIntervalMs = prevF,
                )
                seqNum.addAndGet(packets.size.toLong())
                packets.forEach { rtvsConnection.send(it) }

                if (isKeyFrame) lastIFrameMs = nowMs
                lastFrameMs = nowMs
            }
        }
        codec.releaseOutputBuffer(idx, false)
    }

    companion object {
        private const val TAG = "VideoEncoder"
    }
}
