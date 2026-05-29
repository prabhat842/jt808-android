package com.example.jt808terminal.protocol

/**
 * JT808-2013 §4.4 — low-level frame encode/decode.
 *
 * Message structure (§4.4.1):
 *   0x7E | Header | Body | Checksum | 0x7E
 *
 * Escape rules (§4.4.2):
 *   Applied AFTER checksum to Header+Body+Checksum bytes (NOT to the 0x7E delimiters).
 *   0x7D → 0x7D 0x01
 *   0x7E → 0x7D 0x02
 *
 * Header layout — JT808-2013 §4.4.3 Table 2 (non-versioned, no sub-package, 12 bytes):
 *   [0-1]  Message ID    WORD
 *   [2-3]  Body props    WORD  bits 0-9=bodyLen, bits 10-12=encryption(000), bit13=subPkg(0)
 *   [4-9]  Phone number  BCD[6]  12 digits of terminal SIM number
 *   [10-11] Serial num   WORD
 *
 * Checksum (§4.4.4):
 *   XOR of every byte from start of header through end of body (exclusive of delimiters).
 */
object Jt808Codec {

    /** Maximum body bytes per single JT808 packet — body length field is 10 bits. */
    const val MAX_BODY_PER_PACKET = 1023

    /**
     * Encodes a (possibly large) body as one or more JT808 frames.
     *
     * When [body] fits in a single packet (≤ [MAX_BODY_PER_PACKET] bytes) this returns
     * a list with one element identical to [encodeFrame].
     *
     * When [body] exceeds the limit it is split into chunks and each chunk is sent as a
     * sub-packet frame (bit 13 of bodyProps = 1) per JT808-2013 §4.4.3 Table 2.
     * All sub-packets share the same [seqNum]; the 4-byte sub-packet header inserted
     * after the 12-byte main header carries [totalPackets WORD, currentNum WORD (1-based)].
     */
    fun encodeFrames(msgId: Int, phoneNumber: String, seqNum: Int, body: ByteArray): List<ByteArray> {
        if (body.size <= MAX_BODY_PER_PACKET) {
            return listOf(encodeFrame(msgId, phoneNumber, seqNum, body))
        }
        val chunks = body.asList().chunked(MAX_BODY_PER_PACKET)
        val total  = chunks.size
        return chunks.mapIndexed { idx, chunk ->
            encodeSubPacketFrame(msgId, phoneNumber, seqNum, chunk.toByteArray(), total, idx + 1)
        }
    }

    private fun encodeSubPacketFrame(
        msgId: Int, phoneNumber: String, seqNum: Int,
        chunk: ByteArray, total: Int, current: Int,
    ): ByteArray {
        val header  = buildSubPacketHeader(msgId, phoneNumber, seqNum, chunk.size, total, current)
        val payload = header + chunk
        val checksum = payload.fold(0) { acc, b -> acc xor (b.toInt() and 0xFF) } and 0xFF
        val escaped  = escape(payload + byteArrayOf(checksum.toByte()))
        return byteArrayOf(0x7E.toByte()) + escaped + byteArrayOf(0x7E.toByte())
    }

    private fun buildSubPacketHeader(
        msgId: Int, phoneNumber: String, seqNum: Int,
        chunkLen: Int, total: Int, current: Int,
    ): ByteArray {
        // 12-byte main header + 4-byte sub-packet info block
        val h = ByteArray(16)
        h[0] = (msgId shr 8).toByte()
        h[1] = (msgId and 0xFF).toByte()
        // bodyProps: bits 0-9 = chunkLen, bit 13 = 1 (sub-package flag)
        val props = 0x2000 or (chunkLen and 0x03FF)
        h[2] = (props shr 8).toByte()
        h[3] = (props and 0xFF).toByte()
        writeBcd(h, 4, phoneNumber, 6)
        h[10] = (seqNum shr 8).toByte()
        h[11] = (seqNum and 0xFF).toByte()
        // Sub-packet info — JT808-2013 §4.4.3 Table 2
        h[12] = (total   shr 8).toByte(); h[13] = (total   and 0xFF).toByte()
        h[14] = (current shr 8).toByte(); h[15] = (current and 0xFF).toByte()
        return h
    }

    /** Encodes a complete JT808 frame ready to write to the socket. */
    fun encodeFrame(msgId: Int, phoneNumber: String, seqNum: Int, body: ByteArray): ByteArray {
        val header = buildHeader(msgId, phoneNumber, seqNum, body.size)
        val payload = header + body
        val checksum = payload.fold(0) { acc, b -> acc xor (b.toInt() and 0xFF) } and 0xFF
        val escaped = escape(payload + byteArrayOf(checksum.toByte()))
        return byteArrayOf(0x7E.toByte()) + escaped + byteArrayOf(0x7E.toByte())
    }

    // JT808-2013 §4.4.3 Table 2 — 12-byte header (no versioning, no sub-package)
    private fun buildHeader(msgId: Int, phoneNumber: String, seqNum: Int, bodyLen: Int): ByteArray {
        val h = ByteArray(12)
        h[0] = (msgId shr 8).toByte()
        h[1] = (msgId and 0xFF).toByte()
        // Body properties: bits 0-9 = bodyLen, bits 10-15 = 0 (no encryption, no sub-package)
        h[2] = ((bodyLen shr 8) and 0x03).toByte()
        h[3] = (bodyLen and 0xFF).toByte()
        // Phone number BCD[6]: left-pad to 12 digits, encode as BCD
        writeBcd(h, 4, phoneNumber, 6)
        h[10] = (seqNum shr 8).toByte()
        h[11] = (seqNum and 0xFF).toByte()
        return h
    }

    /** Writes [byteCount] BCD bytes of [digits] into [buf] at [offset], left-padded with '0'. */
    private fun writeBcd(buf: ByteArray, offset: Int, digits: String, byteCount: Int) {
        val s = digits.filter { it.isDigit() }.padStart(byteCount * 2, '0')
            .takeLast(byteCount * 2)
        for (i in 0 until byteCount) {
            val hi = s[i * 2].digitToInt()
            val lo = s[i * 2 + 1].digitToInt()
            buf[offset + i] = ((hi shl 4) or lo).toByte()
        }
    }

    /** Escape: 0x7D → 0x7D 0x01, 0x7E → 0x7D 0x02 — JT808-2013 §4.4.2 */
    private fun escape(data: ByteArray): ByteArray {
        val out = ArrayList<Byte>(data.size + 4)
        for (b in data) {
            when (b.toInt() and 0xFF) {
                0x7D -> { out.add(0x7D.toByte()); out.add(0x01.toByte()) }
                0x7E -> { out.add(0x7D.toByte()); out.add(0x02.toByte()) }
                else -> out.add(b)
            }
        }
        return out.toByteArray()
    }

    /** Unescape: reverse of escape. */
    private fun unescape(data: ByteArray): ByteArray {
        val out = ArrayList<Byte>(data.size)
        var i = 0
        while (i < data.size) {
            if ((data[i].toInt() and 0xFF) == 0x7D && i + 1 < data.size) {
                when (data[i + 1].toInt() and 0xFF) {
                    0x01 -> { out.add(0x7D.toByte()); i += 2; continue }
                    0x02 -> { out.add(0x7E.toByte()); i += 2; continue }
                }
            }
            out.add(data[i])
            i++
        }
        return out.toByteArray()
    }

    data class DecodedFrame(val msgId: Int, val seqNum: Int, val body: ByteArray)

    /**
     * Decodes the raw bytes between 0x7E delimiters.
     * Returns null if checksum fails or frame is malformed.
     *
     * Header formats (JT808-2013 §4.4.3 Table 2):
     *   Standard (bit 14 of bodyProps = 0):  12 bytes — phone BCD[6], seqNum at offset 10
     *   Versioned (bit 14 of bodyProps = 1):  17 bytes — version(1) + phone BCD[10] + seqNum at 15
     *
     * The JT808-2019 extended header is indicated by bodyProps bit 14 = 1.
     * Wire-verified: server sends 17-byte headers; our uplink uses standard 12-byte headers.
     */
    fun decodeFrame(raw: ByteArray): DecodedFrame? {
        val bytes = unescape(raw)
        if (bytes.size < 13) return null

        val expected = bytes.dropLast(1).fold(0) { acc, b -> acc xor (b.toInt() and 0xFF) } and 0xFF
        if (expected != (bytes.last().toInt() and 0xFF)) return null

        val msgId    = u16(bytes, 0)
        val bodyProps = u16(bytes, 2)
        val bodyLen  = bodyProps and 0x03FF
        val hasSubPkg   = (bodyProps and 0x2000) != 0
        // Bit 14 = JT808-2019 versioned header: 1 version byte + 10-byte phone instead of 6-byte
        val isVersioned = (bodyProps and 0x4000) != 0

        val headerSize = if (isVersioned) 17 else 12   // seqNum always at last 2 bytes of header
        val seqNum    = u16(bytes, headerSize - 2)
        val bodyStart = headerSize + if (hasSubPkg) 4 else 0

        if (bytes.size < bodyStart + bodyLen + 1) return null
        return DecodedFrame(msgId, seqNum, bytes.copyOfRange(bodyStart, bodyStart + bodyLen))
    }

    private fun u16(buf: ByteArray, offset: Int) =
        ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)

    /**
     * Stateful reader that splits a raw TCP byte stream into individual frames.
     * A frame is all bytes between two 0x7E delimiters (delimiters excluded).
     */
    class FrameReader {
        private val buffer = ArrayList<Byte>(512)
        private var inFrame = false

        fun feed(data: ByteArray): List<ByteArray> {
            val frames = mutableListOf<ByteArray>()
            for (b in data) {
                if ((b.toInt() and 0xFF) == 0x7E) {
                    if (inFrame && buffer.isNotEmpty()) {
                        frames.add(buffer.toByteArray())
                        buffer.clear()
                    }
                    inFrame = true
                } else if (inFrame) {
                    buffer.add(b)
                }
            }
            return frames
        }
    }
}
