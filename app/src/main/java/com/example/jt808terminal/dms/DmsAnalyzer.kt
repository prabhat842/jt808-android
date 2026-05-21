package com.example.jt808terminal.dms

interface DmsAnalyzer {
    fun analyzeFrame(frame: ByteArray): DmsState
}
