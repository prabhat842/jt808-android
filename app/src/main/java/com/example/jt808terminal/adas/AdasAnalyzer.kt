package com.example.jt808terminal.adas

interface AdasAnalyzer {
    fun analyzeFrame(frame: ByteArray): AdasEvent
}
