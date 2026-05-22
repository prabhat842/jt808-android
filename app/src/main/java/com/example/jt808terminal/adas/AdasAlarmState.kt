package com.example.jt808terminal.adas

// Shared alarm state written by AdasEngine (HandlerThread) and read by LocationReporter.
// @Volatile ensures cross-thread visibility; individual field reads are safe for reporting use.
//
// JT808-2013 Table 24 alarm bit mapping:
//   fcwActive      → bit 29 (Collision warning)
//   pedestrianRisk → bit 3  (Risk warning — zero-clear after server ack)
//   ldwActive      → bit 3  (Risk warning, shared with pedestrian)
//   overSpeedWarn  → bit 13 (Over speed warning)
//   overSpeedAlarm → bit 1  (Over speed alarm)
class AdasAlarmState {
    @Volatile var fcwActive: Boolean = false
    @Volatile var pedestrianRisk: Boolean = false
    @Volatile var ldwActive: Boolean = false
    @Volatile var overSpeedWarning: Boolean = false
    @Volatile var overSpeedAlarm: Boolean = false

    // Written by LocationReporter each cycle so AdasEngine can gate speed-only alarms.
    @Volatile var currentSpeedKph: Float = 0f

    fun hasAlarm(): Boolean =
        fcwActive || pedestrianRisk || ldwActive || overSpeedWarning || overSpeedAlarm
}
