package com.example.jt808terminal.protocol

/**
 * Maps JT808 alarm bits and DMS alarm levels to autonomous media capture actions.
 *
 * Per JT808-2013 §7.9 and JT1078-2016 §6, terminals capture and upload media when
 * specific alarms fire — without waiting for a platform 0x8801 command.
 *
 * Android note: VIDEO_CLIP actions send a JPEG snapshot (mediaType=0) because
 * real-time MP4 extraction from the DVR mid-recording is not practical. The DVR
 * segment is tagged via DvrManager.markAlarm() for server-side FTP retrieval.
 */
object AlarmMediaPolicy {

    enum class MediaKind { SNAPSHOT, VIDEO_CLIP }

    /**
     * @param kind         SNAPSHOT or VIDEO_CLIP (VIDEO_CLIP → JPEG on Android)
     * @param durationSec  clip length; 0 for snapshots
     * @param channelId    camera channel (1 = front-facing)
     * @param eventCode    JT808-2013 Table 52: 0=platform 1=alarm 2=robbery 3=collision
     * @param overlayLabel short label for logging / future overlay use
     */
    data class Action(
        val kind: MediaKind,
        val durationSec: Int,
        val channelId: Int,
        val eventCode: Int,
        val overlayLabel: String,
    )

    // ── JT808 alarm bit index → action ────────────────────────────────────────

    /**
     * Returns the media capture action for a JT808 alarm bit rising edge,
     * or null if this alarm bit does not require autonomous media upload.
     *
     * Bit assignments — JT808-2013 Table 24:
     *   bit 1  = overspeed alarm
     *   bit 3  = risk warning (pedestrian / LDW)
     *   bit 13 = overspeed warning
     *   bit 14 = fatigue driving
     *   bit 29 = collision warning (FCW / ADAS)
     */
    fun forAlarmBit(bitIndex: Int): Action? = when (bitIndex) {
        1  -> Action(MediaKind.SNAPSHOT,   0,  1, 1, "Overspeed")
        3  -> Action(MediaKind.SNAPSHOT,   0,  1, 1, "Risk Warning")
        13 -> Action(MediaKind.SNAPSHOT,   0,  1, 1, "Overspeed Warning")
        14 -> Action(MediaKind.VIDEO_CLIP, 10, 1, 1, "Fatigue Driving")
        29 -> Action(MediaKind.VIDEO_CLIP, 10, 1, 3, "Collision Warning")
        else -> null
    }

    // ── DMS alarm level → actions ─────────────────────────────────────────────

    /**
     * Returns all media capture actions for a DMS alarm onset, in upload order.
     * Mirrors the simulator's AlarmMediaPolicy.forDmsAlarmActions.
     */
    fun forDmsAlarmActions(dmsAlarmType: Int): List<Action> = when (dmsAlarmType) {
        DMS_FATIGUE -> listOf(
            Action(MediaKind.VIDEO_CLIP, 10, 1, 1, "Eye Closure"),
            Action(MediaKind.SNAPSHOT,   0,  1, 1, "Eye Closure"),
        )
        DMS_DISTRACTION -> listOf(Action(MediaKind.SNAPSHOT, 0, 1, 1, "Distraction"))
        DMS_PHONE       -> listOf(Action(MediaKind.SNAPSHOT, 0, 1, 1, "Phone Use"))
        DMS_SMOKING     -> listOf(Action(MediaKind.SNAPSHOT, 0, 1, 1, "Smoking"))
        DMS_NO_SEATBELT -> listOf(Action(MediaKind.SNAPSHOT, 0, 1, 1, "No Seatbelt"))
        else -> emptyList()
    }

    // DMS alarm type constants
    const val DMS_NONE        = 0
    const val DMS_FATIGUE     = 1   // PERCLOS level ≥ 1 (warning or danger)
    const val DMS_DISTRACTION = 2
    const val DMS_PHONE       = 3
    const val DMS_SMOKING     = 4
    const val DMS_NO_SEATBELT = 5
}
