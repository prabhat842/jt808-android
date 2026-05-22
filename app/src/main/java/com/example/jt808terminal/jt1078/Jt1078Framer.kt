package com.example.jt808terminal.jt1078

/**
 * Builds JT/T 1078-2016 audio/video RTP-like packets.
 *
 * Packet wire format — JT/T 1078-2016 §6.1 Table 19:
 *   [0-3]   4B  Magic 0x30 0x31 0x63 0x64
 *   [4]     1B  RTP flags: V(2b)=2, P(1b)=0, X(1b)=0, CC(4b)=0 → 0x80
 *   [5]     1B  M(1b) | PT(7b)   M=1 on last sub-packet; PT from Table 12
 *   [6-7]   2B  Sequence number (WORD, per channel, wraps at 65535)
 *   [8-13]  6B  Terminal SIM phone BCD[6]
 *   [14]    1B  Logical channel number
 *   [15]    1B  Data type (high nibble) | sub-package type (low nibble)
 *   --- conditional fields ---
 *   [16-23] 8B  Timestamp ms (Long) — NOT present when dataType = 0x04 (passthrough)
 *   [24-25] 2B  Last I-frame interval ms (WORD) — video frames only
 *   [26-27] 2B  Last frame interval ms (WORD)   — video frames only
 *   --- always present ---
 *   [28-29] 2B  Payload length (WORD)
 *   [30+]   NB  Payload (≤ MAX_PAYLOAD_BYTES per packet)
 *
 * Sub-package type nibble (low nibble of byte [15]):
 *   0x0 = ATOMIC (complete frame fits in one packet)
 *   0x1 = FIRST  (first packet of a split frame)
 *   0x2 = LAST   (last packet of a split frame)
 *   0x3 = MIDDLE (middle packet of a split frame)
 *
 * Payload type codes — JT/T 1078-2016 Table 12:
 *   98 = H.264 video
 *    6 = G.711A audio (A-law)
 */
object Jt1078Framer {

    // Magic bytes — JT/T 1078-2016 §6.1
    private val MAGIC = byteArrayOf(0x30, 0x31, 0x63, 0x64)

    // Per spec §12 critical constraints: max 950 bytes body per packet
    const val MAX_PAYLOAD_BYTES = 950

    // Frame data type nibbles — JT/T 1078-2016 §6.1 Table 19
    const val TYPE_VIDEO_I    = 0x0
    const val TYPE_VIDEO_P    = 0x1
    const val TYPE_VIDEO_B    = 0x2
    const val TYPE_AUDIO      = 0x3
    const val TYPE_PASSTHROUGH = 0x4

    // Payload type codes — JT/T 1078-2016 Table 12
    const val PT_H264  = 98   // H.264
    const val PT_G711A =  6   // G.711A (A-law) — NOT G.711U (code 7)

    /**
     * Builds one or more JT1078 packets for an H.264 video NAL.
     * SPS+PPS must already be prepended to the first I-frame payload by the caller.
     * NALs larger than 950 bytes are split into FIRST/MIDDLE/LAST sub-packets.
     */
    fun buildVideoPackets(
        phoneNumber: String,
        channel: Int,
        seqBase: Long,
        payload: ByteArray,
        isKeyFrame: Boolean,
        timestampMs: Long,
        prevIFrameIntervalMs: Int = 0,
        prevFrameIntervalMs: Int = 0,
    ): List<ByteArray> = buildPackets(
        phone = phoneNumber,
        channel = channel,
        seqBase = seqBase,
        payload = payload,
        frameType = if (isKeyFrame) TYPE_VIDEO_I else TYPE_VIDEO_P,
        payloadType = PT_H264,
        timestampMs = timestampMs,
        prevIInterval = prevIFrameIntervalMs,
        prevFInterval = prevFrameIntervalMs,
        isVideo = true,
    )

    /**
     * Builds one or more JT1078 packets for a G.711A audio frame.
     * Per spec §4.4: frame size = 160 samples = 20ms per packet.
     */
    fun buildAudioPackets(
        phoneNumber: String,
        channel: Int,
        seqBase: Long,
        payload: ByteArray,
        timestampMs: Long,
    ): List<ByteArray> = buildPackets(
        phone = phoneNumber,
        channel = channel,
        seqBase = seqBase,
        payload = payload,
        frameType = TYPE_AUDIO,
        payloadType = PT_G711A,
        timestampMs = timestampMs,
        prevIInterval = 0,
        prevFInterval = 0,
        isVideo = false,
    )

    private fun buildPackets(
        phone: String, channel: Int, seqBase: Long,
        payload: ByteArray, frameType: Int, payloadType: Int,
        timestampMs: Long, prevIInterval: Int, prevFInterval: Int, isVideo: Boolean,
    ): List<ByteArray> {
        if (payload.isEmpty()) return emptyList()
        val chunks = chunk(payload, MAX_PAYLOAD_BYTES)
        val n = chunks.size
        return chunks.mapIndexed { i, chunk ->
            val subPkg = when {
                n == 1 -> 0x0  // ATOMIC
                i == 0 -> 0x1  // FIRST
                i == n - 1 -> 0x2  // LAST
                else -> 0x3       // MIDDLE
            }
            encodePacket(
                phone = phone, channel = channel,
                seq = ((seqBase + i) and 0xFFFFL).toInt(),
                payload = chunk, frameType = frameType, subPkg = subPkg,
                payloadType = payloadType,
                isLast = subPkg == 0x0 || subPkg == 0x2,
                ts = timestampMs, prevI = prevIInterval, prevF = prevFInterval,
                isVideo = isVideo,
            )
        }
    }

    private fun encodePacket(
        phone: String, channel: Int, seq: Int,
        payload: ByteArray, frameType: Int, subPkg: Int, payloadType: Int,
        isLast: Boolean, ts: Long, prevI: Int, prevF: Int, isVideo: Boolean,
    ): ByteArray {
        val isPassthrough = frameType == TYPE_PASSTHROUGH
        val out = ArrayList<Byte>(32 + payload.size)

        // [0-3] Magic — JT/T 1078-2016 §6.1
        for (b in MAGIC) out.add(b)
        // [4] V=2, P=0, X=0, CC=0
        out.add(0x80.toByte())
        // [5] M bit (1 if last sub-packet) | payload type
        out.add(((if (isLast) 0x80 else 0x00) or (payloadType and 0x7F)).toByte())
        // [6-7] Sequence number (WORD)
        out.add((seq shr 8).toByte()); out.add((seq and 0xFF).toByte())
        // [8-13] Terminal SIM BCD[6]
        writeBcd(out, phone, 6)
        // [14] Logical channel number
        out.add(channel.toByte())
        // [15] (dataType << 4) | subPackage
        out.add(((frameType shl 4) or subPkg).toByte())
        // [16-23] Timestamp ms (8-byte Long) — conditional: NOT for passthrough
        if (!isPassthrough) {
            writeLong(out, ts)
        }
        // [24-27] Intervals — conditional: video only, not passthrough
        if (isVideo && !isPassthrough) {
            writeShort(out, prevI)
            writeShort(out, prevF)
        }
        // Payload length + payload
        writeShort(out, payload.size)
        for (b in payload) out.add(b)
        return out.toByteArray()
    }

    private fun chunk(data: ByteArray, size: Int): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        var off = 0
        while (off < data.size) {
            result.add(data.copyOfRange(off, minOf(off + size, data.size)))
            off += size
        }
        return result
    }

    private fun writeBcd(out: ArrayList<Byte>, digits: String, byteCount: Int) {
        val s = digits.filter { it.isDigit() }.padStart(byteCount * 2, '0').takeLast(byteCount * 2)
        for (i in 0 until byteCount) {
            out.add(((s[i * 2].digitToInt() shl 4) or s[i * 2 + 1].digitToInt()).toByte())
        }
    }

    private fun writeLong(out: ArrayList<Byte>, v: Long) {
        for (shift in 56 downTo 0 step 8) out.add((v ushr shift).toByte())
    }

    private fun writeShort(out: ArrayList<Byte>, v: Int) {
        out.add((v shr 8).toByte()); out.add((v and 0xFF).toByte())
    }
}
