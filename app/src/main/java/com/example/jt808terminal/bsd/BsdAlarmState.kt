package com.example.jt808terminal.bsd

// Shared alarm state written by BsdEngine (on AdasEngine's HandlerThread) and read by LocationReporter.
//
// JT808-2013 Table 24 alarm bit mapping:
//   leftBlindSpot / rightBlindSpot → bit 29 (Collision warning)
class BsdAlarmState {
    @Volatile var leftBlindSpot: Boolean = false
    @Volatile var rightBlindSpot: Boolean = false

    fun hasAlarm(): Boolean = leftBlindSpot || rightBlindSpot
}
