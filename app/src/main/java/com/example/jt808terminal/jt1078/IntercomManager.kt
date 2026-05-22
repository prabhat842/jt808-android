package com.example.jt808terminal.jt1078

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import com.example.jt808terminal.jt1078.alaw.ALawCodec
import java.util.concurrent.atomic.AtomicLong

/**
 * Two-way intercom for JT/T 1078-2016 dataType=2 (0x9101 §5.5.1).
 *
 * Uplink:  mic PCM → G.711A → JT1078 audio packets → RtvsConnection.send
 * Downlink: RtvsConnection frames → G.711A payload → PCM → AudioTrack
 *
 * Audio spec: G.711A (A-law, PT=6), 8 kHz mono, 20 ms frames (160 samples).
 * NOT G.711U — per project constraint.
 */
class IntercomManager(
    private val phoneNumber: String,
    private val channel: Int,
    private val conn: RtvsConnection,
) {
    // 8 kHz, 160 samples = 20 ms — G.711 standard frame size
    private val sampleRate = 8_000
    private val frameSamples = 160

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    @Volatile private var running = false
    private var micThread: Thread? = null
    private val seqNum = AtomicLong(0)

    fun start() {
        if (running) return
        running = true
        conn.onDownlinkPacket = { frame -> onDownlink(frame) }
        startCapture()
        startPlayback()
        Log.i(TAG, "Intercom started ch=$channel")
    }

    fun stop() {
        running = false
        conn.onDownlinkPacket = null
        micThread?.join(2_000L)
        try { audioRecord?.stop() } catch (_: Exception) {}
        audioRecord?.release()
        audioRecord = null
        try { audioTrack?.stop() } catch (_: Exception) {}
        audioTrack?.release()
        audioTrack = null
        Log.i(TAG, "Intercom stopped ch=$channel")
    }

    private fun startCapture() {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "AudioRecord not available")
            return
        }
        @Suppress("MissingPermission")
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, frameSamples * 2 * 8),
        )
        audioRecord!!.startRecording()

        micThread = Thread({
            val pcm = ShortArray(frameSamples)
            while (running) {
                val read = audioRecord?.read(pcm, 0, frameSamples) ?: break
                if (read <= 0) continue
                val alaw = ALawCodec.encodeBuffer(pcm, read)
                val now = System.currentTimeMillis()
                val packets = Jt1078Framer.buildAudioPackets(phoneNumber, channel, seqNum.get(), alaw, now)
                seqNum.addAndGet(packets.size.toLong())
                packets.forEach { conn.send(it) }
                conn.flush()
            }
        }, "intercom-mic-ch$channel")
        micThread!!.start()
    }

    private fun startPlayback() {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        audioTrack = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            maxOf(minBuf, frameSamples * 2 * 8),
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        audioTrack!!.play()
    }

    // Called from RtvsConnection's IO coroutine for each inbound JT1078 frame.
    private fun onDownlink(frame: ByteArray) {
        val payload = extractAudioPayload(frame) ?: return
        val pcm = ALawCodec.decodeBuffer(payload)
        audioTrack?.write(pcm, 0, pcm.size)
    }

    // Extracts the G.711A payload bytes from a complete JT1078 audio frame.
    // Audio frame header = 4B magic + 12B fixed + 8B timestamp + 2B length — §6.1 Table 19
    private fun extractAudioPayload(frame: ByteArray): ByteArray? {
        if (frame.size < 28) return null
        // Verify magic
        if (frame[0] != 0x30.toByte() || frame[1] != 0x31.toByte() ||
            frame[2] != 0x63.toByte() || frame[3] != 0x64.toByte()) return null

        val dataTypeNibble = (frame[15].toInt() and 0xFF) shr 4
        val isPassthrough = dataTypeNibble == Jt1078Framer.TYPE_PASSTHROUGH
        val isVideo = dataTypeNibble == Jt1078Framer.TYPE_VIDEO_I ||
                      dataTypeNibble == Jt1078Framer.TYPE_VIDEO_P ||
                      dataTypeNibble == Jt1078Framer.TYPE_VIDEO_B

        val headerEnd = 16 + when {
            isPassthrough -> 0
            isVideo       -> 12
            else          -> 8
        }
        if (frame.size < headerEnd + 2) return null

        val payloadLen = ((frame[headerEnd].toInt() and 0xFF) shl 8) or
                         (frame[headerEnd + 1].toInt() and 0xFF)
        val payloadStart = headerEnd + 2
        if (frame.size < payloadStart + payloadLen) return null

        return frame.copyOfRange(payloadStart, payloadStart + payloadLen)
    }

    companion object {
        private const val TAG = "IntercomManager"
    }
}
