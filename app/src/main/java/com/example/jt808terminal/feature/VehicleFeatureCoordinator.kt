package com.example.jt808terminal.feature

import com.example.jt808terminal.adas.AdasAnalyzer
import com.example.jt808terminal.adas.AdasEvent
import com.example.jt808terminal.bsd.BsdAnalyzer
import com.example.jt808terminal.bsd.BsdEvent
import com.example.jt808terminal.dms.DmsAnalyzer
import com.example.jt808terminal.dms.DmsState

class VehicleFeatureCoordinator(
    private val dmsAnalyzer: DmsAnalyzer? = null,
    private val adasAnalyzer: AdasAnalyzer? = null,
    private val bsdAnalyzer: BsdAnalyzer? = null,
) {
    fun analyzeCameraFrame(frame: ByteArray): VehicleSnapshot {
        return VehicleSnapshot(
            dms = dmsAnalyzer?.analyzeFrame(frame),
            adas = adasAnalyzer?.analyzeFrame(frame),
            bsd = bsdAnalyzer?.analyzeFrame(frame),
        )
    }
}

data class VehicleSnapshot(
    val dms: DmsState? = null,
    val adas: AdasEvent? = null,
    val bsd: BsdEvent? = null,
)
