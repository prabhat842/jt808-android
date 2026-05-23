package com.example.jt808terminal.media

import android.util.Log
import com.example.jt808terminal.db.DvrSegmentDao
import java.io.File

/**
 * Evicts recordings from disk and the DVR segment database — Phase 8.
 *
 * Three eviction passes, applied in order:
 *   1. Alarm-clip age  — alarm clips older than [ALARM_CLIP_MAX_AGE_MS] (24 h).
 *   2. Size eviction   — if total stored size still exceeds [maxSizeBytes], delete
 *                        normal segments oldest-first until under the limit.
 *
 * Normal segments are already managed by DvrManager's per-channel rolling ring
 * (24 × 5-min = 2-hour window).  StorageManager handles the cross-channel size
 * cap and the alarm-clip age limit that DvrManager does not track.
 */
object StorageManager {

    private const val TAG = "StorageManager"

    /** Alarm clips are kept for 24 hours, then evicted here. */
    private val ALARM_CLIP_MAX_AGE_MS = 24L * 3600 * 1000

    suspend fun evict(dao: DvrSegmentDao, maxSizeMb: Int) {
        val maxSizeBytes = maxSizeMb.toLong() * 1024L * 1024L
        val now = System.currentTimeMillis()

        // Pass 1: expired alarm clips
        for (clip in dao.getExpiredAlarmClips(now - ALARM_CLIP_MAX_AGE_MS)) {
            File(clip.filePath).delete()
            dao.delete(clip.id)
            Log.i(TAG, "Evicted alarm clip ${clip.filePath}")
        }

        // Pass 2: size cap on normal segments
        val totalBytes = dao.totalSizeBytes()
        if (totalBytes <= maxSizeBytes) {
            Log.d(TAG, "Storage OK ${totalBytes / 1_048_576}MB / ${maxSizeMb}MB")
            return
        }
        var remaining = totalBytes
        for (seg in dao.getNormalSegmentsForSizeEviction()) {
            if (remaining <= maxSizeBytes) break
            remaining -= seg.fileSizeBytes
            File(seg.filePath).delete()
            dao.delete(seg.id)
            Log.i(TAG, "Size evicted ${seg.filePath}")
        }
    }
}
