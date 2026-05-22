package com.example.jt808terminal.jt1078

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.util.concurrent.atomic.AtomicLong

/**
 * H.264 video encoder using MediaCodec in Surface mode.
 *
 * Camera binding is intentionally NOT here — TerminalService owns all CameraX lifecycle
 * bindings so the camera can stay on for DMS/ADAS/BSD regardless of RTVS connection state.
 * Call getInputSurface() to get the Surface for the CameraX Preview use case.
 *
 * SPS/PPS: captured from CODEC_CONFIG buffer, prepended to first IDR frame — spec §4.3.
 * Subpackets: NAL units > 950 bytes split via Jt1078Framer — JT/T 1078-2016 §6.1.
 */
class VideoEncoder(
    private val phoneNumber: String,
    private val channel: Int,
    private val rtvsConnection: RtvsConnection,
    val videoWidth: Int  = 1280,
    val videoHeight: Int = 720,
    frameRate: Int = 25,
    bitrateBps: Int = 1_000_000,
    keyFrameIntervalSec: Int = 2,
) {
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null

    @Volatile private var spsPpsBuffer: ByteArray? = null
    @Volatile private var spsPpsSent = false

    private val seqNum = AtomicLong(0)
    private var lastIFrameMs = 0L
    private var lastFrameMs = 0L

    @Volatile private var encoderRunning = false
    private var outputThread: Thread? = null

    init {
        setupCodec(videoWidth, videoHeight, frameRate, bitrateBps, keyFrameIntervalSec)
    }

    private fun setupCodec(w: Int, h: Int, fps: Int, bps: Int, keyInterval: Int) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
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

    /** Starts the codec and output thread. Camera binding is done by TerminalService. */
    fun start() {
        codec!!.start()
        startOutputLoop()
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

    /** Surface that must be given to the CameraX Preview use case. */
    fun getInputSurface(): Surface = inputSurface ?: error("Encoder not initialized")

    private fun startOutputLoop() {
        encoderRunning = true
        outputThread = Thread({
            val info = MediaCodec.BufferInfo()
            while (encoderRunning) {
                try {
                    val idx = codec?.dequeueOutputBuffer(info, 10_000L) ?: break
                    if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Log.d(TAG, "Output format changed")
                    } else if (idx >= 0) {
                        processOutputBuffer(idx, info)
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

        val isConfig   = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
        val isKeyFrame = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
        val nowMs = System.currentTimeMillis()

        if (info.size > 0) {
            val raw = ByteArray(info.size)
            buf.position(info.offset)
            buf.get(raw)

            if (isConfig) {
                spsPpsBuffer = raw   // save SPS+PPS for first I-frame — spec §4.3
                Log.d(TAG, "SPS+PPS captured (${raw.size}B)")
            } else {
                val payload = if (isKeyFrame && !spsPpsSent) {
                    spsPpsSent = true
                    val sps = spsPpsBuffer
                    if (sps != null) sps + raw else raw   // prepend SPS+PPS to first IDR
                } else raw

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
