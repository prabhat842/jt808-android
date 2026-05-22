package com.example.jt808terminal.bsd

import android.util.Log
import com.google.mlkit.vision.objects.DetectedObject

/**
 * Blind Spot Detection engine — Phase 6.
 *
 * Runs on the same HandlerThread as AdasEngine (called from AdasEngine's detection callback)
 * so it piggybacks on the single back-camera ImageAnalysis without needing its own use case.
 *
 * BSD zones — each occupies the outermost 25% of frame width.
 * An object whose bounding box centre-x falls inside a BSD zone AND whose area exceeds
 * BSD_MIN_AREA_RATIO is counted as a blind-spot intrusion.
 *
 * Lane-change interlock: BSD left/right flags are exposed via BsdAlarmState so the platform
 * can suppress a lane-change command (0x9102 or custom) when the blind spot is occupied.
 *
 * Alarm encoding — JT808-2013 Table 24 bit 29 (Collision warning) is raised when either
 * blind spot is occupied.  The left/right distinction is available in BsdAlarmState for
 * UI display and future custom additional-info TLV encoding.
 */
class BsdEngine(private val alarmState: BsdAlarmState) {

    companion object {
        private const val TAG = "BsdEngine"
        // Outermost 25% of frame width on each side = BSD zone.
        private const val BSD_ZONE_FRACTION = 0.25f
        // Object must be at least 2% of total frame area to count (filters far-away noise).
        private const val BSD_MIN_AREA_RATIO = 0.02f
    }

    /**
     * Called from AdasEngine's detection callback (HandlerThread).
     * [frameWidth] / [frameHeight] are the ImageProxy dimensions used to compute ratios.
     */
    fun analyzeObjects(objects: List<DetectedObject>, frameWidth: Int, frameHeight: Int) {
        if (frameWidth == 0 || frameHeight == 0) return
        val frameArea = frameWidth * frameHeight
        val leftBound = (frameWidth * BSD_ZONE_FRACTION).toInt()
        val rightBound = (frameWidth * (1f - BSD_ZONE_FRACTION)).toInt()

        var left = false
        var right = false

        for (obj in objects) {
            val bbox = obj.boundingBox
            val objArea = bbox.width() * bbox.height()
            if (objArea.toFloat() / frameArea < BSD_MIN_AREA_RATIO) continue

            val cx = bbox.centerX()
            if (cx < leftBound)  left  = true
            if (cx > rightBound) right = true
        }

        if (alarmState.leftBlindSpot != left || alarmState.rightBlindSpot != right) {
            Log.d(TAG, "BSD: left=$left right=$right")
        }
        alarmState.leftBlindSpot  = left
        alarmState.rightBlindSpot = right
    }

    fun reset() {
        alarmState.leftBlindSpot  = false
        alarmState.rightBlindSpot = false
    }
}
