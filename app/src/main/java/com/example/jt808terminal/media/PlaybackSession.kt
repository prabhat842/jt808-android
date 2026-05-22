package com.example.jt808terminal.media

import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.example.jt808terminal.jt1078.Jt1078Framer
import com.example.jt808terminal.jt1078.RtvsConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Streams one recorded MP4 file over an RtvsConnection using JT/T 1078-2016 packet format.
 * Created when a 0x9201 playback request arrives — Phase 7.
 *
 * Flow (JT/T 1078-2016 §5.6.3):
 *   1. Terminal first replies with 0x1205 resource list (done by TerminalService before creating this).
 *   2. Then establishes TCP to the RTVS server and starts streaming.
 *   3. 0x9202 control events are delivered via [control] channel at any time.
 *
 * Playback reads an MP4 file with [MediaExtractor], converts H.264 from AVCC to Annex B,
 * prepends SPS+PPS on each I-frame, and sends frames through [Jt1078Framer].
 * AAC audio tracks are skipped; the server receives video only.
 */
class PlaybackSession(
    private val phoneNumber: String,
    private val channel: Int,
    private val entry: RecordingEntry,
    private val conn: RtvsConnection,
    private val scope: CoroutineScope,
    /** Initial playback method from 0x9201: 0=normal, 1=fast-forward, 2=keyframe-snap */
    private val playbackMethod: Int = 0,
    /** Initial multiplier from 0x9201: 0=invalid,1=1x,2=2x,3=4x,4=8x,5=16x */
    private val speedMultiplier: Int = 0,
) {
    enum class ControlCmd(val code: Int) {
        START(0), PAUSE(1), STOP(2), FAST_FORWARD(3),
        KEYFRAME_SNAP(4), SEEK(5), KEYFRAME_ONLY(6)
    }

    /** Deliver 0x9202 control commands here from TerminalService. */
    val control = Channel<Pair<ControlCmd, Long>>(capacity = 4) // (cmd, seekMs)

    private var job: Job? = null

    fun start() {
        job = scope.launch(Dispatchers.IO) { runPlayback() }
    }

    fun stop() {
        job?.cancel()
        conn.disconnect()
    }

    // -------------------------------------------------------------------------

    private suspend fun runPlayback() {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(entry.filePath)

            val videoTrack = findVideoTrack(extractor) ?: run {
                Log.w(TAG, "No video track in ${entry.filePath}")
                return
            }
            extractor.selectTrack(videoTrack)

            val format = extractor.getTrackFormat(videoTrack)
            val spsPps = extractSpsPps(format)

            var seqNum   = 0L
            var paused   = false
            var keyOnly  = playbackMethod == 3
            var speedDiv = speedMultiplierToDelay(speedMultiplier)   // delay divisor

            var lastIFrameMs  = 0L
            var lastFrameMs   = 0L
            var prevSampleUs  = -1L

            Log.i(TAG, "Playback started: ${entry.filePath}")

            while (isActive) {
                // Drain any pending control commands (non-blocking)
                drainControl(control) { cmd, seekMs ->
                    when (cmd) {
                        ControlCmd.START         -> paused = false
                        ControlCmd.PAUSE         -> paused = true
                        ControlCmd.STOP          -> { stop(); return@drainControl }
                        ControlCmd.FAST_FORWARD  -> { paused = false; speedDiv = 1 }
                        ControlCmd.KEYFRAME_SNAP -> { paused = false; speedDiv = 1 }
                        ControlCmd.SEEK          -> {
                            extractor.seekTo(seekMs * 1_000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                            prevSampleUs = -1L
                        }
                        ControlCmd.KEYFRAME_ONLY -> { keyOnly = true }
                    }
                }

                if (paused) { delay(50); continue }

                val buf = ByteBuffer.allocate(MAX_SAMPLE_SIZE)
                val sampleSize = extractor.readSampleData(buf, 0)
                if (sampleSize < 0) {
                    Log.i(TAG, "Playback EOF")
                    break
                }

                val isKeyFrame  = (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                val sampleUs    = extractor.sampleTime

                if (keyOnly && !isKeyFrame) {
                    extractor.advance(); continue
                }

                // Pace frames at original speed adjusted by multiplier
                if (prevSampleUs >= 0) {
                    val deltaMicros = sampleUs - prevSampleUs
                    if (deltaMicros > 0 && speedDiv > 0) {
                        delay((deltaMicros / 1000L) / speedDiv)
                    }
                }
                prevSampleUs = sampleUs

                val rawBytes = ByteArray(sampleSize).also { buf.get(it) }
                val annexBBytes = avccToAnnexB(rawBytes)

                val payload = if (isKeyFrame && spsPps != null) spsPps + annexBBytes
                              else annexBBytes

                val nowMs = entry.startMs + sampleUs / 1_000L
                val iFrInterval = if (isKeyFrame) (nowMs - lastIFrameMs).toInt().coerceAtLeast(0) else 0
                val frInterval  = (nowMs - lastFrameMs).toInt().coerceAtLeast(0)
                if (isKeyFrame) lastIFrameMs = nowMs
                lastFrameMs = nowMs

                val packets = Jt1078Framer.buildVideoPackets(
                    phoneNumber            = phoneNumber,
                    channel                = channel,
                    seqBase                = seqNum,
                    payload                = payload,
                    isKeyFrame             = isKeyFrame,
                    timestampMs            = nowMs,
                    prevIFrameIntervalMs   = iFrInterval,
                    prevFrameIntervalMs    = frInterval,
                )
                seqNum += packets.size
                for (pkt in packets) conn.send(pkt)

                extractor.advance()
            }
        } catch (ex: Exception) {
            Log.e(TAG, "Playback error: ${ex.message}")
        } finally {
            extractor.release()
            conn.disconnect()
            Log.i(TAG, "Playback session ended ch=$channel")
        }
    }

    // -------------------------------------------------------------------------

    /** Finds the first H.264 video track in the extractor. */
    private fun findVideoTrack(ex: MediaExtractor): Int? {
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/avc")) return i
        }
        return null
    }

    /**
     * Extracts SPS+PPS from the MP4 AVCDecoderConfigurationRecord (csd-0) and
     * returns them in Annex B format (0x00 0x00 0x00 0x01 prefix each NAL).
     * JT/T 1078-2016: SPS+PPS must be prepended to the first I-frame payload.
     */
    private fun extractSpsPps(format: MediaFormat): ByteArray? {
        val csd0 = format.getByteBuffer("csd-0") ?: return null
        val bytes = ByteArray(csd0.remaining()).also { csd0.get(it) }
        if (bytes.size < 7) return null
        return try {
            val out = ByteArrayOutputStream()
            var pos = 5  // skip configVersion(1) + profile(1) + compat(1) + level(1) + lenSize(1)
            val numSps = bytes[pos++].toInt() and 0x1F
            repeat(numSps) {
                val len = u16(bytes, pos); pos += 2
                out.write(ANNEX_B_START); out.write(bytes, pos, len); pos += len
            }
            val numPps = bytes[pos++].toInt() and 0xFF
            repeat(numPps) {
                val len = u16(bytes, pos); pos += 2
                out.write(ANNEX_B_START); out.write(bytes, pos, len); pos += len
            }
            out.toByteArray()
        } catch (e: Exception) {
            Log.w(TAG, "extractSpsPps failed: ${e.message}"); null
        }
    }

    /**
     * Converts AVCC format (4-byte length prefix per NAL) to Annex B (0x00 0x00 0x00 0x01 prefix).
     * MediaExtractor always returns AVCC-formatted samples for H.264 tracks in MP4.
     */
    private fun avccToAnnexB(avcc: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(avcc.size + 4)
        var offset = 0
        while (offset + 4 <= avcc.size) {
            val nalLen = ((avcc[offset].toInt() and 0xFF) shl 24) or
                         ((avcc[offset + 1].toInt() and 0xFF) shl 16) or
                         ((avcc[offset + 2].toInt() and 0xFF) shl 8) or
                         (avcc[offset + 3].toInt() and 0xFF)
            if (nalLen <= 0 || offset + 4 + nalLen > avcc.size) break
            out.write(ANNEX_B_START)
            out.write(avcc, offset + 4, nalLen)
            offset += 4 + nalLen
        }
        return out.toByteArray()
    }

    private inline fun drainControl(
        ch: Channel<Pair<ControlCmd, Long>>,
        block: (ControlCmd, Long) -> Unit,
    ) {
        var r = ch.tryReceive()
        while (r.isSuccess) {
            val (cmd, ms) = r.getOrThrow()
            block(cmd, ms)
            r = ch.tryReceive()
        }
    }

    private fun speedMultiplierToDelay(mult: Int): Long = when (mult) {
        0, 1 -> 1L; 2 -> 2L; 3 -> 4L; 4 -> 8L; 5 -> 16L; else -> 1L
    }

    private fun u16(b: ByteArray, off: Int) =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    companion object {
        private const val TAG = "PlaybackSession"
        private const val MAX_SAMPLE_SIZE = 512 * 1024
        private val ANNEX_B_START = byteArrayOf(0, 0, 0, 1)
    }
}
