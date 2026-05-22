package com.example.jt808terminal.dms

// Shared alarm state written by DmsEngine (HandlerThread) and read by LocationReporter (coroutine).
// @Volatile ensures cross-thread visibility for each field. Individual field reads are
// safe for the reporting use case — a slightly stale value delays an alarm by one report cycle.
class DmsAlarmState {
    // Behaviour flags — JT1078-2016 extension to JT808-2013 Table 27 (ID 0x18):
    //   bit 0 = fatigue (PERCLOS threshold breached)
    //   bit 1 = phone use (reserved — needs custom model)
    //   bit 2 = smoking  (reserved — needs custom model)
    @Volatile var behaviourFlags: Int = 0

    // PERCLOS value as 0-100 fatigue degree, included in 0x18 TLV body byte.
    @Volatile var fatigueDegree: Int = 0

    // Whether a driver face is currently detected.
    @Volatile var faceDetected: Boolean = false

    fun hasAlarm(): Boolean = behaviourFlags != 0
}
