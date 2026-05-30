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

    // Alarm severity per spec §5.2: 0=clear, 1=warning (PERCLOS 35-50%), 2=danger (>50%).
    // Level 2 triggers an immediate 0x0200 report so the server auto-sends 0x9101.
    @Volatile var alarmLevel: Int = 0

    // Written by LocationReporter each cycle so DmsEngine can gate no-face check on speed.
    @Volatile var currentSpeedKph: Float = 0f

    // UI display fields — per-frame detection results for on-screen status panel.
    @Volatile var eyesClosed: Boolean = false
    @Volatile var isYawning: Boolean = false
    @Volatile var yawnCount: Int = 0
    @Volatile var headDistracted: Boolean = false  // large yaw/pitch from forward gaze

    fun hasAlarm(): Boolean = behaviourFlags != 0
}
