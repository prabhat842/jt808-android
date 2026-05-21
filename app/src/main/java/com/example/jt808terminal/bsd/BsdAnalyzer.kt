package com.example.jt808terminal.bsd

interface BsdAnalyzer {
    fun analyzeFrame(frame: ByteArray): BsdEvent
}
