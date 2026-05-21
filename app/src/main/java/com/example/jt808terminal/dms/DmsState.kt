package com.example.jt808terminal.dms

data class DmsState(
    val faceDetected: Boolean = false,
    val eyesClosed: Boolean = false,
    val distracted: Boolean = false,
    val fatigueDegree: Int = 0,
    val seatbeltWorn: Boolean = true,
)
