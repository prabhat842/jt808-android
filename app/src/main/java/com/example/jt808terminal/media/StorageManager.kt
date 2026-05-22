package com.example.jt808terminal.media

import android.util.Log
import java.io.File

/**
 * Evicts old recordings to keep local storage within configured limits — Phase 8.
 *
 * Two-pass strategy (applied in order):
 *   1. Age eviction  — delete any complete recording whose end time is older than [maxAgeMs].
 *   2. Size eviction — if total size of remaining complete recordings still exceeds
 *                      [maxSizeBytes], delete oldest-first until under the limit.
 *
 * In-progress recordings (endMs == 0) are never touched.
 */
object StorageManager {

    private const val TAG = "StorageManager"

    private val MAX_AGE_MS = 7L * 24 * 3600 * 1000   // 7 days

    /**
     * Run eviction against [index].
     * [maxSizeMb] comes from AppSettings.maxStorageMb (server-configurable).
     */
    fun evict(index: RecordingIndex, maxSizeMb: Int) {
        val maxSizeBytes = maxSizeMb.toLong() * 1024L * 1024L
        val now = System.currentTimeMillis()

        // Pass 1: age eviction
        for (entry in index.getAll()) {
            if (!entry.isComplete) continue
            if (now - entry.endMs > MAX_AGE_MS) {
                deleteEntry(index, entry)
            }
        }

        // Pass 2: size eviction — oldest first
        val remaining = index.getAll()
            .filter { it.isComplete }
            .sortedBy { it.startMs }
        var totalBytes = remaining.sumOf { it.fileSizeBytes }
        for (entry in remaining) {
            if (totalBytes <= maxSizeBytes) break
            totalBytes -= entry.fileSizeBytes
            deleteEntry(index, entry)
        }

        Log.i(TAG, "Eviction complete — ${index.getAll().filter { it.isComplete }.size} segment(s) remain")
    }

    private fun deleteEntry(index: RecordingIndex, entry: RecordingEntry) {
        val deleted = File(entry.filePath).delete()
        index.remove(entry.id)
        Log.i(TAG, "Evicted ${entry.filePath} (deleted=$deleted, ${entry.fileSizeBytes}B)")
    }
}
