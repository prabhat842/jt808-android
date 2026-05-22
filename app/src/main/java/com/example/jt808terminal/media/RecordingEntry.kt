package com.example.jt808terminal.media

/**
 * Metadata for one recorded video segment stored on device.
 *
 * Corresponds to one row in the 0x1205 resource list — JT/T 1078-2016 §5.6.2 Table 23.
 *
 * [alarmFlags]: JT808-2013 Table 24 bits set during this segment — used to answer
 *   alarm-filtered 0x9205 queries.
 */
data class RecordingEntry(
    val id: Long,               // System.currentTimeMillis() at recording start; used as stable ID
    val channel: Int,           // logical channel number (Table 2 in JT/T 1076-2016)
    val startMs: Long,          // epoch ms (device local, GMT+8 for BCD encoding)
    val endMs: Long,            // epoch ms; 0 while recording is in progress
    val filePath: String,       // absolute path to the MP4 file
    val fileSizeBytes: Long,    // file size in bytes; 0 while recording
    val alarmFlags: Long = 0L,  // union of all JT808 alarm bits raised during this segment
    val resourceType: Int = 2,  // 0=AV, 1=audio, 2=video — we record video-only
    val streamType: Int = 1,    // 1=main stream, 2=sub-stream
    val memoryType: Int = 1,    // 1=main memory, 2=disaster recovery
) {
    val isComplete: Boolean get() = endMs > 0
}
