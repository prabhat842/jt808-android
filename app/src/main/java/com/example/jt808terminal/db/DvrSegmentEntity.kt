package com.example.jt808terminal.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.jt808terminal.media.RecordingEntry

/**
 * Room entity for one DVR video segment.
 *
 * Replaces the JSON-file RecordingIndex.  Indexed on (channel, startMs) for the
 * 0x9205 resource-list query and on alarmFlags for alarm-filtered queries.
 *
 * [isAlarmClip] true  → segment was triggered by a DMS/ADAS alarm; exempt from
 *                        the 2-hour rolling-ring eviction but subject to the
 *                        24-hour alarm-clip age limit.
 *              false → regular DVR segment; oldest normal segment deleted when
 *                        the per-channel ring exceeds MAX_NORMAL_SEGMENTS (24).
 */
@Entity(
    tableName = "dvr_segment",
    indices = [
        Index(value = ["channel", "startMs"]),
        Index(value = ["alarmFlags"]),
        Index(value = ["isAlarmClip"]),
    ],
)
data class DvrSegmentEntity(
    @PrimaryKey val id: Long,           // epoch ms at recording start — stable unique ID
    val channel: Int,
    val startMs: Long,
    val endMs: Long,                    // 0 while recording is in progress
    val filePath: String,
    val fileSizeBytes: Long,            // 0 while recording
    val alarmFlags: Long = 0L,          // JT808-2013 Table 24 alarm bits set during segment
    val isAlarmClip: Boolean = false,   // true = alarm-triggered, exempt from rolling deletion
    val resourceType: Int = 2,          // 0=AV, 1=audio, 2=video
    val streamType: Int = 1,            // 1=main stream, 2=sub-stream
    val memoryType: Int = 1,            // 1=main memory, 2=disaster recovery
) {
    val isComplete: Boolean get() = endMs > 0

    fun toRecordingEntry() = RecordingEntry(
        id            = id,
        channel       = channel,
        startMs       = startMs,
        endMs         = endMs,
        filePath      = filePath,
        fileSizeBytes = fileSizeBytes,
        alarmFlags    = alarmFlags,
        resourceType  = resourceType,
        streamType    = streamType,
        memoryType    = memoryType,
    )
}
