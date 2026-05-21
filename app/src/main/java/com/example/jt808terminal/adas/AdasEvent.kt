package com.example.jt808terminal.adas

data class AdasEvent(
    val laneDeparture: Boolean = false,
    val forwardCollisionRisk: Boolean = false,
    val pedestrianRisk: Boolean = false,
)
