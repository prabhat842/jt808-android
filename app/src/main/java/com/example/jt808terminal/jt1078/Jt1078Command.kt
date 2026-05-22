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

    // -------------------------------------------------------------------------
    // Phase 7: playback and file management
    // -------------------------------------------------------------------------

    /**
     * Parsed 0x9201 Remote video playback request — JT/T 1078-2016 §5.6.3 Table 24.
     *
     * [playbackMethod]: 0=normal, 1=fast-forward, 2=keyframe-snap, 3=keyframe-only, 4=single-frame
     * [speedMultiplier]: valid for methods 1/2: 0=invalid,1=1x,2=2x,3=4x,4=8x,5=16x
     */
    data class Request9201(
        val host: String,
        val tcpPort: Int,
        val udpPort: Int,
        val channel: Int,
        val avType: Int,            // 0=AV,1=audio,2=video,3=video-or-AV
        val streamType: Int,        // 0=any,1=main,2=sub
        val memoryType: Int,        // 0=any,1=main,2=disaster
        val playbackMethod: Int,
        val speedMultiplier: Int,
        val startMs: Long,          // epoch ms (GMT+8)
        val endMs: Long,            // epoch ms; 0 = play all
    )

    /**
     * Decodes a 0x9201 body — JT/T 1078-2016 §5.6.3 Table 24.
     * Variable-length IP prefix; startTime/endTime are BCD[6] in GMT+8.
     */
    fun parse9201(body: ByteArray): Request9201? {
        if (body.isEmpty()) return null
        var pos = 0
        val ipLen  = body[pos++].toInt() and 0xFF
        if (pos + ipLen + 22 > body.size) return null
        val host   = String(body, pos, ipLen, Charsets.UTF_8); pos += ipLen
        val tcpPort = u16(body, pos); pos += 2
        val udpPort = u16(body, pos); pos += 2
        val channel = body[pos++].toInt() and 0xFF
        val avType  = body[pos++].toInt() and 0xFF
        val streamType  = body[pos++].toInt() and 0xFF
        val memType     = body[pos++].toInt() and 0xFF
        val playMethod  = body[pos++].toInt() and 0xFF
        val speedMult   = body[pos++].toInt() and 0xFF
        val startMs = bcdToEpochMs(body, pos); pos += 6
        val endMs   = bcdToEpochMs(body, pos)
        return Request9201(host, tcpPort, udpPort, channel, avType, streamType,
            memType, playMethod, speedMult, startMs, endMs)
    }

    /**
     * Parsed 0x9202 Remote video playback control — JT/T 1078-2016 §5.6.4 Table 25.
     *
     * [control]: 0=start,1=pause,2=end,3=fast-forward,4=keyframe-snap,5=seek,6=keyframe-only
     * [seekMs]: epoch ms seek position; valid only when control=5
     */
    data class Control9202(
        val channel: Int,
        val control: Int,
        val speedMultiplier: Int,
        val seekMs: Long,           // epoch ms; valid only when control == 5
    )

    /** Decodes a 0x9202 body — JT/T 1078-2016 §5.6.4 Table 25. */
    fun parse9202(body: ByteArray): Control9202? {
        if (body.size < 2) return null
        val channel   = body[0].toInt() and 0xFF
        val control   = body[1].toInt() and 0xFF
        val speedMult = if (body.size > 2) body[2].toInt() and 0xFF else 0
        val seekMs    = if (control == 5 && body.size >= 9) bcdToEpochMs(body, 3) else 0L
        return Control9202(channel, control, speedMult, seekMs)
    }

    /**
     * Parsed 0x9206 File upload command — JT/T 1078-2016 §5.6.5 Table 26.
     * Variable-length fields: serverAddress, username, password, uploadPath.
     *
     * [taskConditions]: bit0=WiFi, bit1=LAN, bit2=3G/4G
     */
    data class Upload9206(
        val ftpHost: String,
        val ftpPort: Int,
        val username: String,
        val password: String,
        val uploadPath: String,
        val channel: Int,
        val startMs: Long,
        val endMs: Long,
        val alarmFlags: Long,
        val resourceType: Int,      // 0=AV,1=audio,2=video,3=video-or-AV
        val streamType: Int,
        val storageLocation: Int,
        val taskConditions: Int,    // bit0=WiFi,bit1=LAN,bit2=3G4G
        val seqNum: Int,            // original 0x9206 message seqNum for 0x1206 reply
    )

    /**
     * Decodes a 0x9206 body — JT/T 1078-2016 §5.6.5 Table 26.
     * [bodySeqNum] is the JT808 message sequence number of the 0x9206 frame.
     */
    fun parse9206(body: ByteArray, bodySeqNum: Int): Upload9206? {
        return try {
            var pos = 0
            val k = body[pos++].toInt() and 0xFF
            val ftpHost = String(body, pos, k, Charsets.UTF_8); pos += k
            val ftpPort = u16(body, pos); pos += 2
            val l = body[pos++].toInt() and 0xFF
            val username = String(body, pos, l, Charsets.UTF_8); pos += l
            val m = body[pos++].toInt() and 0xFF
            val password = String(body, pos, m, Charsets.UTF_8); pos += m
            val n = body[pos++].toInt() and 0xFF
            val uploadPath = String(body, pos, n, Charsets.UTF_8); pos += n
            val channel = body[pos++].toInt() and 0xFF
            val startMs = bcdToEpochMs(body, pos); pos += 6
            val endMs   = bcdToEpochMs(body, pos); pos += 6
            val alarmFlags = u64(body, pos); pos += 8
            val resourceType = body[pos++].toInt() and 0xFF
            val streamType   = body[pos++].toInt() and 0xFF
            val storageLoc   = body[pos++].toInt() and 0xFF
            val taskCond     = body[pos].toInt() and 0xFF
            Upload9206(ftpHost, ftpPort, username, password, uploadPath,
                channel, startMs, endMs, alarmFlags, resourceType, streamType,
                storageLoc, taskCond, bodySeqNum)
        } catch (e: Exception) { null }
    }

    /**
     * Parsed 0x9207 File upload control — JT/T 1078-2016 §5.6.7 Table 28.
     * [uploadControl]: 0=suspend, 1=continue, 2=cancel
     */
    data class Control9207(val responseSeq: Int, val uploadControl: Int)

    fun parse9207(body: ByteArray): Control9207? {
        if (body.size < 3) return null
        return Control9207(u16(body, 0), body[2].toInt() and 0xFF)
    }

    // -------------------------------------------------------------------------

    /**
     * Decodes a BCD[6] timestamp (YY MM DD hh mm ss in GMT+8) to epoch ms.
     * JT/T 1078-2016 uses the same BCD[6] convention as JT808-2013 §4.4.3.
     * All-zero returns 0 (the spec's sentinel for "no time condition").
     */
    fun bcdToEpochMs(buf: ByteArray, offset: Int): Long {
        if (offset + 5 >= buf.size) return 0L
        var allZero = true
        for (i in 0..5) if (buf[offset + i] != 0.toByte()) { allZero = false; break }
        if (allZero) return 0L
        val year  = fromBcd(buf[offset + 0])
        val month = fromBcd(buf[offset + 1])
        val day   = fromBcd(buf[offset + 2])
        val hour  = fromBcd(buf[offset + 3])
        val min   = fromBcd(buf[offset + 4])
        val sec   = fromBcd(buf[offset + 5])
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai"))
        cal.set(2000 + year, month - 1, day, hour, min, sec)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun fromBcd(b: Byte): Int {
        val v = b.toInt() and 0xFF
        return (v ushr 4) * 10 + (v and 0x0F)
    }

    private fun u16(buf: ByteArray, offset: Int) =
        ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)

    private fun u64(buf: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 0..7) v = (v shl 8) or (buf[offset + i].toLong() and 0xFF)
        return v
    }
}
