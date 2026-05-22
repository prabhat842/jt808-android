package com.example.jt808terminal.jt1078.alaw

// G.711 A-law (A-law, code 6) encode/decode — ITU-T G.711 §2.2
// Input: 16-bit signed linear PCM  →  Output: 8-bit A-law byte (XOR'd with 0x55 per spec).
// Segments 0-7 cover a 13-bit magnitude after shifting PCM right by 3.
object ALawCodec {

    fun encode(pcm: Short): Byte {
        var x = pcm.toInt()
        val sign: Int
        if (x >= 0) {
            sign = 0x80
        } else {
            sign = 0x00
            x = -x - 1                // two's complement: -1→0, -32768→32767
        }
        x = minOf(x shr 3, 4095)     // 13-bit magnitude 0-4095

        val seg: Int; val mant: Int
        when {
            x < 32   -> { seg = 0; mant = x shr 1 }
            x < 64   -> { seg = 1; mant = (x - 32) shr 1 }
            x < 128  -> { seg = 2; mant = (x - 64) shr 2 }
            x < 256  -> { seg = 3; mant = (x - 128) shr 3 }
            x < 512  -> { seg = 4; mant = (x - 256) shr 4 }
            x < 1024 -> { seg = 5; mant = (x - 512) shr 5 }
            x < 2048 -> { seg = 6; mant = (x - 1024) shr 6 }
            else     -> { seg = 7; mant = (x - 2048) shr 7 }
        }
        return ((sign or (seg shl 4) or mant) xor 0x55).toByte()
    }

    fun decode(alaw: Byte): Short {
        var a = (alaw.toInt() and 0xFF) xor 0x55
        val positive = (a and 0x80) != 0
        a = a and 0x7F
        val seg  = (a shr 4) and 0x07
        val mant = a and 0x0F

        // Reconstruct 13-bit midpoint of the quantization interval.
        val x13 = when (seg) {
            0    -> (mant shl 1) or 1
            1    -> 32 + (mant shl 1) + 1
            else -> (64 shl (seg - 2)) + (mant shl seg) + (1 shl (seg - 1))
        }
        val x16 = x13 shl 3
        return if (positive) x16.toShort() else (-(x16 + 1)).toShort()
    }

    fun encodeBuffer(pcm: ShortArray, len: Int = pcm.size): ByteArray =
        ByteArray(len) { encode(pcm[it]) }

    fun decodeBuffer(alaw: ByteArray): ShortArray =
        ShortArray(alaw.size) { decode(alaw[it]) }
}
