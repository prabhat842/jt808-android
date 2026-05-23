package com.example.jt808terminal.media

import android.util.Log
import com.example.jt808terminal.db.DvrSegmentDao
import com.example.jt808terminal.db.DvrSegmentEntity
import kotlinx.coroutines.runBlocking

/**
 * Thin façade over [DvrSegmentDao] that preserves the synchronous API used by
 * TerminalService, DvrManager, and StorageManager.
 *
 * All callers already run on Dispatchers.IO or Dispatchers.Default coroutines,
 * so `runBlocking` here does not block the main thread.
 *
 * The old JSON flat-file implementation has been replaced by Room.
 */
class RecordingIndex(private val dao: DvrSegmentDao) {

    fun add(entry: RecordingEntry) = runBlocking {
        dao.insert(entry.toEntity())
    }

    fun complete(id: Long, endMs: Long, fileSizeBytes: Long) = runBlocking {
        dao.complete(id, endMs, fileSizeBytes)
        Log.d(TAG, "Segment $id finalised ${fileSizeBytes}B")
    }

    /** OR-merges [alarmFlags] into the most recently started (incomplete) segment. */
    fun markAlarm(alarmFlags: Long) = runBlocking {
        val all = dao.getAll()
        val current = all.lastOrNull { !it.isComplete } ?: return@runBlocking
        dao.addAlarmFlags(current.id, alarmFlags)
    }

    /** Marks a segment as an alarm clip, exempting it from rolling-ring deletion. */
    fun markAlarmClip(id: Long) = runBlocking { dao.markAsAlarmClip(id) }

    fun query(
        channel: Int,
        startMs: Long,
        endMs: Long,
        alarmMask: Long,
        resourceType: Int,
    ): List<RecordingEntry> = runBlocking {
        dao.query(channel, startMs, endMs, alarmMask, resourceType).map { it.toRecordingEntry() }
    }

    fun getAll(): List<RecordingEntry> = runBlocking {
        dao.getAll().map { it.toRecordingEntry() }
    }

    fun findById(id: Long): RecordingEntry? = runBlocking {
        dao.getAll().firstOrNull { it.id == id }?.toRecordingEntry()
    }

    fun remove(id: Long) = runBlocking { dao.delete(id) }

    // -- Internal helpers ---------------------------------------------------

    private fun RecordingEntry.toEntity() = DvrSegmentEntity(
        id            = id,
        channel       = channel,
        startMs       = startMs,
        endMs         = endMs,
        filePath      = filePath,
        fileSizeBytes = fileSizeBytes,
        alarmFlags    = alarmFlags,
        isAlarmClip   = false,
        resourceType  = resourceType,
        streamType    = streamType,
        memoryType    = memoryType,
    )

    companion object {
        private const val TAG = "RecordingIndex"
    }
}
