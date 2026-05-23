package com.example.jt808terminal.protocol

import com.example.jt808terminal.core.TerminalConfig
import com.example.jt808terminal.media.RecordingEntry
import java.util.Calendar
import java.util.TimeZone

// Message IDs — JT808-2013 Appendix B / JT1078-2016 Appendix A
object MsgId {
    // Terminal → platform
    const val TERMINAL_GENERAL_RESPONSE = 0x0001
    const val HEARTBEAT               = 0x0002
    const val TERMINAL_REGISTER       = 0x0100
    const val TERMINAL_AUTH           = 0x0102
    const val LOCATION_REPORT         = 0x0200
    const val LOCATION_BATCH_UPLOAD   = 0x0704   // bulk GPS upload — JT808-2013 §8.49
    const val MULTIMEDIA_EVENT_UPLOAD = 0x0800
    const val MULTIMEDIA_DATA_UPLOAD  = 0x0801   // DMS snapshot JPEG

    // JT1078 terminal → platform
    const val UPLOAD_AV_ATTRIBUTES    = 0x1003   // reply to 0x9003
    const val UPLOAD_RESOURCE_LIST    = 0x1205   // reply to 0x9205
    const val FILE_UPLOAD_COMPLETE    = 0x1206   // notify after FTP done

    // Platform → terminal
    const val PLATFORM_GENERAL_RESPONSE = 0x8001
    const val REGISTRATION_RESPONSE   = 0x8100
    const val PARAMETER_SETTING       = 0x8103
    const val AV_ATTRIBUTES_QUERY     = 0x9003
    const val REALTIME_AV_REQUEST     = 0x9101
    const val AV_CONTROL              = 0x9102
    const val AV_STATUS_NOTIFY        = 0x9105
    const val PLAYBACK_REQUEST        = 0x9201
    const val PLAYBACK_CONTROL        = 0x9202
    const val QUERY_RESOURCE_LIST     = 0x9205
    const val FILE_UPLOAD_CMD         = 0x9206
    const val FILE_UPLOAD_CONTROL     = 0x9207
    const val PTZ_CONTROL             = 0x9301
    const val FOCUS_CONTROL           = 0x9302
    const val APERTURE_CONTROL        = 0x9303
    const val WIPER_CONTROL           = 0x9304
    const val INFRARED_CONTROL        = 0x9305
    const val ZOOM_CONTROL            = 0x9306
}

/**
 * Builds JT808-2013 message bodies.
 * Every method is documented with the exact table and section from JT808-2013.
 */
object Jt808Messages {

    /**
     * 0x0100 Terminal registration body — JT808-2013 §8.5 Table 7.
     *
     * Layout (37 bytes fixed + variable VIN/plate):
     *   [0-1]   Province domain ID   WORD   0 = use platform default
     *   [2-3]   City/county domain ID WORD   0 = use platform default
     *   [4-8]   Manufacturer ID       BYTE[5]
     *   [9-28]  Terminal type (model) BYTE[20] padded with 0x00
     *   [29-35] Terminal ID (serial)  BYTE[7]  padded with 0x00
     *   [36]    License plate color   BYTE     0 = unregistered
     *   [37+]   VIN or plate          STRING   GBK; when color==0 this is the VIN
     */
    fun registration(config: TerminalConfig): ByteArray {
        val vinBytes = config.vin.toByteArray(Charsets.UTF_8)
        val buf = ByteArray(37 + vinBytes.size)
        // Province and city domain IDs — left as 0 (platform default)
        writeWord(buf, 0, 0)
        writeWord(buf, 2, 0)
        writeFixedAscii(buf, 4, config.manufacturerId, 5)   // BYTE[5]
        writeFixedAscii(buf, 9, config.terminalModel, 20)   // BYTE[20]
        writeFixedAscii(buf, 29, config.terminalId, 7)      // BYTE[7]
        buf[36] = config.plateColor.toByte()
        vinBytes.copyInto(buf, 37)
        return buf
    }

    /**
     * 0x0102 Terminal authentication body — JT808-2013 §8.8 Table 9.
     * Body is just the authentication code STRING (no length prefix).
     */
    fun authentication(authCode: String): ByteArray =
        authCode.toByteArray(Charsets.UTF_8)

    /**
     * 0x0002 Terminal heartbeat — JT808-2013 §8.3.
     * Message body is null (empty).
     */
    fun heartbeat(): ByteArray = ByteArray(0)

    /**
     * 0x0001 Terminal general response — JT808-2013 §8.1 Table 4.
     *
     * [0-1] Response serial number  WORD  serial of the platform message being acknowledged
     * [2-3] Response ID             WORD  msgId of the platform message being acknowledged
     * [4]   Result                  BYTE  0=success 1=failure 2=incorrect 3=not supporting
     */
    fun generalResponse(responseSeq: Int, responseId: Int, result: Int = 0): ByteArray {
        val buf = ByteArray(5)
        writeWord(buf, 0, responseSeq)
        writeWord(buf, 2, responseId)
        buf[4] = result.toByte()
        return buf
    }

    /**
     * 0x0200 Location information report body — JT808-2013 §8.18.
     *
     * Basic information (Table 23, 28 bytes fixed):
     *   [0-3]   Alarm sign   DWORD  — Table 24 alarm bits
     *   [4-7]   Status       DWORD  — Table 25 status bits
     *   [8-11]  Latitude     DWORD  — |deg| × 10^6, unsigned; S/W flags in status word
     *   [12-15] Longitude    DWORD  — |deg| × 10^6, unsigned
     *   [16-17] Altitude     WORD   — metres
     *   [18-19] Speed        WORD   — 1/10 km/h
     *   [20-21] Direction    WORD   — 0-359°, north=0, clockwise
     *   [22-27] Time         BCD[6] — YY-MM-DD-hh-mm-ss in GMT+8 (§4.4.3)
     *
     * Followed by Additional info TLV items (Table 26):
     *   each item: ID(1 byte) + Length(1 byte) + Value(N bytes)
     *   IDs sourced from Table 27.
     */
    fun locationReport(
        alarmFlags: Long = 0L,
        statusFlags: Long,
        latitudeDeg: Double,
        longitudeDeg: Double,
        altitudeM: Int = 0,
        speedKph: Double = 0.0,
        headingDeg: Int = 0,
        timestampMs: Long = System.currentTimeMillis(),
        signalStrength: Int = 0,
        satelliteCount: Int = 0,
        extraItems: ByteArray = ByteArray(0),
    ): ByteArray {
        val lat = Math.round(Math.abs(latitudeDeg) * 1_000_000).toInt()
        val lon = Math.round(Math.abs(longitudeDeg) * 1_000_000).toInt()
        val speed = Math.round(speedKph * 10).toInt().coerceIn(0, 65535)
        val heading = Math.floorMod(headingDeg, 360)
        val timeBytes = bcdTimestampGmt8(timestampMs)   // BCD[6] in GMT+8

        val out = ArrayList<Byte>(64)
        // Fixed basic information — JT808-2013 §8.18 Table 23
        writeDWordL(out, alarmFlags.toInt())
        writeDWordL(out, statusFlags.toInt())
        writeDWordL(out, lat)
        writeDWordL(out, lon)
        writeWordL(out, altitudeM)
        writeWordL(out, speed)
        writeWordL(out, heading)
        for (b in timeBytes) out.add(b)

        // Additional info 0x30: wireless signal strength — Table 27
        out.add(0x30); out.add(1); out.add(signalStrength.toByte())
        // Additional info 0x31: GNSS satellite count — Table 27
        out.add(0x31); out.add(1); out.add(satelliteCount.toByte())
        // Caller-supplied extra TLV items (DMS/ADAS alarm items from Phase 4+)
        for (b in extraItems) out.add(b)

        return out.toByteArray()
    }

    // -- JT1078 terminal attribute upload (0x1003) -------------------------

    /**
     * 0x1003 Upload A/V Attributes body — JT/T 1078-2016 §5.4 Table 13.
     *
     * [0]   Audio input codec       BYTE  6=G.711A (Table 12)
     * [1]   Audio channel count     BYTE  1=mono
     * [2]   Sample rate             BYTE  0=8 kHz
     * [3]   Sample bit depth        BYTE  1=16-bit
     * [4-5] Audio frame length      WORD  160 samples = 20 ms
     * [6]   Audio output supported  BYTE  1=yes (intercom capable)
     * [7]   Video codec             BYTE  98=H.264 (Table 12)
     * [8]   Video channel count     BYTE  1 (front camera only)
     * [9]   Video reserved          BYTE  4
     */
    fun avAttributes(): ByteArray = byteArrayOf(
        6,             // audio codec: G.711A — JT/T 1078-2016 Table 12
        1,             // audio channels: mono
        0,             // sample rate: 0 = 8 kHz
        1,             // sample bits: 1 = 16-bit
        0, 160.toByte(), // audio frame length WORD = 160 samples (20 ms)
        1,             // audio output supported: 1 = yes
        98.toByte(),   // video codec: H.264 — JT/T 1078-2016 Table 12
        1,             // video channels
        4,             // reserved
    )

    /**
     * 0x1205 Audio/Video resource list — JT/T 1078-2016 §5.6.2 Table 22/23.
     *
     * Header (6 bytes):
     *   [0-1] Response seq  WORD   echoes the 0x9205 message serial number
     *   [2-5] File count    DWORD  number of entries that follow
     *
     * Each entry (28 bytes) — Table 23:
     *   [0]     logical channel  BYTE
     *   [1-6]   start time       BCD[6]  yy-mm-dd-hh-mm-ss (GMT+8)
     *   [7-12]  end time         BCD[6]
     *   [13-20] warning sign     64BITS  JT808 alarm flags
     *   [21]    resource type    BYTE    0=AV,1=audio,2=video
     *   [22]    stream type      BYTE    1=main,2=sub
     *   [23]    memory type      BYTE    1=main,2=disaster-recovery
     *   [24-27] file size        DWORD   bytes
     */
    fun resourceList(responseSeq: Int, entries: List<RecordingEntry>): ByteArray {
        val buf = ArrayList<Byte>(6 + entries.size * 28)
        buf.add((responseSeq shr 8).toByte()); buf.add((responseSeq and 0xFF).toByte())
        val count = entries.size
        buf.add((count ushr 24).toByte()); buf.add((count ushr 16).toByte())
        buf.add((count ushr 8).toByte());  buf.add((count and 0xFF).toByte())
        for (e in entries) {
            buf.add(e.channel.toByte())
            for (b in bcdTimestampGmt8(e.startMs)) buf.add(b)
            for (b in bcdTimestampGmt8(e.endMs))   buf.add(b)
            val alarm = e.alarmFlags
            for (shift in 56 downTo 0 step 8) buf.add((alarm ushr shift).toByte())
            buf.add(e.resourceType.toByte())
            buf.add(e.streamType.toByte())
            buf.add(e.memoryType.toByte())
            val sz = e.fileSizeBytes.toInt()
            buf.add((sz ushr 24).toByte()); buf.add((sz ushr 16).toByte())
            buf.add((sz ushr 8).toByte());  buf.add((sz and 0xFF).toByte())
        }
        return buf.toByteArray()
    }

    /** 0x1205 with zero entries — sent when no recordings match the 0x9205 query. */
    fun resourceListEmpty(responseSeq: Int): ByteArray =
        word(responseSeq) + dword(0)

    /**
     * 0x1206 File upload completion notification — JT/T 1078-2016 §5.6.6 Table 27.
     *   [0-1] Response seq (WORD) — echoes the 0x9206 seqNum
     *   [2]   Result (BYTE) — 0=success, 1=failure
     */
    fun fileUploadComplete(responseSeq: Int, success: Boolean): ByteArray =
        word(responseSeq) + byteArrayOf(if (success) 0 else 1)

    // -- Additional info item builders for DMS/ADAS/BSD (Phase 4+) --------

    /**
     * Additional info 0x14 — video alarm DWORD — JT808-2013 Table 27 / JT1078-2016 Table 14.
     * Bit 8 = ADAS forward collision, bit 9 = BSD lane change, bit 10 = no seatbelt,
     * bit 11 = lane departure — as custom bits per spec §2.4.
     */
    fun additionalVideoAlarm(alarmWord: Int): ByteArray =
        byteArrayOf(0x14, 4) + dword(alarmWord)

    /**
     * Additional info 0x15 — video signal loss per channel bitmask DWORD — Table 27.
     * Bit 0 = channel 1, bit 1 = channel 2, …
     */
    fun additionalVideoSignalLoss(channelMask: Int): ByteArray =
        byteArrayOf(0x15, 4) + dword(channelMask)

    /**
     * Additional info 0x18 — abnormal driving behaviour — JT808-2013 Table 27.
     * WORD behaviour flags + BYTE fatigue degree (0-100).
     * Bit 0 = fatigue, bit 1 = phone use, bit 2 = smoking.
     */
    fun additionalDrivingBehavior(flags: Int, fatigueDegree: Int): ByteArray =
        byteArrayOf(0x18, 3) + word(flags) + byteArrayOf(fatigueDegree.toByte())

    // -- BCD timestamp -------------------------------------------------------

    /**
     * Encodes current time as BCD[6] in GMT+8 — JT808-2013 §4.4.3 / Table 23.
     * Format: YY MM DD hh mm ss (each pair as one BCD byte).
     */
    private fun bcdTimestampGmt8(epochMs: Long): ByteArray {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))
        cal.timeInMillis = epochMs
        return byteArrayOf(
            bcd(cal.get(Calendar.YEAR) % 100),
            bcd(cal.get(Calendar.MONTH) + 1),
            bcd(cal.get(Calendar.DAY_OF_MONTH)),
            bcd(cal.get(Calendar.HOUR_OF_DAY)),
            bcd(cal.get(Calendar.MINUTE)),
            bcd(cal.get(Calendar.SECOND)),
        )
    }

    private fun bcd(v: Int): Byte = (((v / 10) shl 4) or (v % 10)).toByte()

    // -- Low-level writers ---------------------------------------------------

    private fun writeWord(buf: ByteArray, offset: Int, v: Int) {
        buf[offset] = (v shr 8).toByte(); buf[offset + 1] = (v and 0xFF).toByte()
    }

    private fun writeFixedAscii(buf: ByteArray, offset: Int, s: String, len: Int) {
        val bytes = s.toByteArray(Charsets.US_ASCII)
        bytes.copyInto(buf, offset, 0, minOf(bytes.size, len))
        // remaining bytes stay 0x00 (ByteArray is zero-initialized)
    }

    private fun writeDWordL(list: ArrayList<Byte>, v: Int) {
        list.add((v ushr 24).toByte()); list.add((v ushr 16).toByte())
        list.add((v ushr 8).toByte()); list.add((v and 0xFF).toByte())
    }

    private fun writeWordL(list: ArrayList<Byte>, v: Int) {
        list.add((v shr 8).toByte()); list.add((v and 0xFF).toByte())
    }

    private fun dword(v: Int) = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), (v and 0xFF).toByte()
    )

    private fun word(v: Int) = byteArrayOf((v shr 8).toByte(), (v and 0xFF).toByte())
}
