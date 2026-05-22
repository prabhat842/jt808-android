package com.example.jt808terminal.jt1078

import java.io.InputStream

// Reassembles complete JT/T 1078-2016 §6.1 frames from a raw TCP byte stream.
// readFrame() blocks until a full frame is available or the stream closes.
class Jt1078StreamParser(private val input: InputStream) {

    companion object {
        private val MAGIC = byteArrayOf(0x30, 0x31, 0x63, 0x64)
    }

    // Returns a complete frame (magic + variable header + payload), null on EOF or I/O error.
    fun readFrame(): ByteArray? {
        if (!syncToMagic()) return null

        // Fixed bytes after magic: flags(1) PT(1) seq(2) phone(6) channel(1) dataType(1) = 12
        val fixed12 = ByteArray(12)
        if (!readFully(fixed12)) return null

        val dataTypeNibble = (fixed12[11].toInt() and 0xFF) shr 4
        val isPassthrough = dataTypeNibble == Jt1078Framer.TYPE_PASSTHROUGH
        val isVideo = dataTypeNibble == Jt1078Framer.TYPE_VIDEO_I ||
                      dataTypeNibble == Jt1078Framer.TYPE_VIDEO_P ||
                      dataTypeNibble == Jt1078Framer.TYPE_VIDEO_B

        // Conditional header bytes — JT/T 1078-2016 §6.1 Table 19
        val extraLen = when {
            isPassthrough -> 0
            isVideo       -> 12   // 8B timestamp + 4B intervals
            else          -> 8    // 8B timestamp only (audio)
        }
        val extra = ByteArray(extraLen)
        if (extraLen > 0 && !readFully(extra)) return null

        val lenBuf = ByteArray(2)
        if (!readFully(lenBuf)) return null
        val payloadLen = ((lenBuf[0].toInt() and 0xFF) shl 8) or (lenBuf[1].toInt() and 0xFF)

        val payload = ByteArray(payloadLen)
        if (payloadLen > 0 && !readFully(payload)) return null

        return MAGIC + fixed12 + extra + lenBuf + payload
    }

    // Scans byte-by-byte until 0x30 0x31 0x63 0x64 is found.
    private fun syncToMagic(): Boolean {
        var matched = 0
        while (matched < 4) {
            val b = input.read()
            if (b < 0) return false
            matched = if (b == (MAGIC[matched].toInt() and 0xFF)) matched + 1
                      else if (b == (MAGIC[0].toInt() and 0xFF)) 1 else 0
        }
        return true
    }

    private fun readFully(buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }
}
