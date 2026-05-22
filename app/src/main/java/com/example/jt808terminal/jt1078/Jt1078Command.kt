package com.example.jt808terminal.jt1078

/**
 * Parsed platform commands related to JT/T 1078-2016 audio/video.
 * Bodies decoded from the raw JT808 message body bytes.
 */
object Jt1078Command {

    /**
     * Parsed 0x9101 Real-time A/V request — JT/T 1078-2016 §5.5.1.
     *
     * dataType values:
     *   0 = audio + video
     *   1 = video only
     *   2 = two-way intercom (Phase 3)
     *   3 = listen / monitor audio
     *   4 = broadcast audio
     *   5 = pass-through
     */
    data class Request9101(
        val host: String,
        val tcpPort: Int,
        val udpPort: Int,
        val channel: Int,
        val dataType: Int,
        val streamType: Int,   // 0=main stream, 1=sub-stream
    )

    /**
     * Decodes a 0x9101 body.
     * Body layout (spec §5.5.1, verified against simulator Jt1078CommandDecoder):
     *   [0]       IP address length (BYTE)  — variable-length STRING, not null-terminated
     *   [1..n]    Server IP address (STRING)
     *   [n+1..2]  TCP port (WORD)
     *   [n+3..4]  UDP port (WORD)
     *   [n+5]     Logical channel number (BYTE)
     *   [n+6]     Data type (BYTE)
     *   [n+7]     Stream type (BYTE)
     */
    fun parse9101(body: ByteArray): Request9101? {
        if (body.isEmpty()) return null
        var pos = 0
        val ipLen = body[pos++].toInt() and 0xFF
        if (pos + ipLen + 6 > body.size) return null
        val host = String(body, pos, ipLen, Charsets.UTF_8)
        pos += ipLen
        val tcpPort = u16(body, pos); pos += 2
        val udpPort = u16(body, pos); pos += 2
        val channel = body[pos++].toInt() and 0xFF
        val dataType = body[pos++].toInt() and 0xFF
        val streamType = if (pos < body.size) body[pos].toInt() and 0xFF else 0
        return Request9101(host, tcpPort, udpPort, channel, dataType, streamType)
    }

    private fun u16(buf: ByteArray, offset: Int) =
        ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)
}
