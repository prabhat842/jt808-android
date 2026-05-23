package com.example.jt808terminal.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DvrSegmentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(segment: DvrSegmentEntity)

    @Query("UPDATE dvr_segment SET endMs = :endMs, fileSizeBytes = :sizeBytes WHERE id = :id")
    suspend fun complete(id: Long, endMs: Long, sizeBytes: Long)

    @Query("UPDATE dvr_segment SET alarmFlags = alarmFlags | :flags WHERE id = :id")
    suspend fun addAlarmFlags(id: Long, flags: Long)

    @Query("UPDATE dvr_segment SET isAlarmClip = 1 WHERE id = :id")
    suspend fun markAsAlarmClip(id: Long)

    @Query("DELETE FROM dvr_segment WHERE id = :id")
    suspend fun delete(id: Long)

    /** All complete segments matching the 0x9205 query filters — JT/T 1078-2016 §5.6.1 Table 21. */
    @Query("""
        SELECT * FROM dvr_segment
        WHERE endMs > 0
          AND (:channel = 0 OR channel = :channel)
          AND (:startMs = 0 OR endMs   >= :startMs)
          AND (:endMs   = 0 OR startMs <= :endMs)
          AND (:alarmMask = 0 OR (alarmFlags & :alarmMask) != 0)
          AND (:resourceType = 3 OR :resourceType = 0 OR resourceType = :resourceType)
        ORDER BY startMs ASC
    """)
    suspend fun query(
        channel: Int,
        startMs: Long,
        endMs: Long,
        alarmMask: Long,
        resourceType: Int,
    ): List<DvrSegmentEntity>

    /** Normal (non-alarm) segments for a channel, oldest first — used by rolling-ring eviction. */
    @Query("""
        SELECT * FROM dvr_segment
        WHERE isAlarmClip = 0 AND channel = :channel
        ORDER BY startMs ASC
    """)
    suspend fun getNormalSegmentsOldestFirst(channel: Int): List<DvrSegmentEntity>

    /** Count of complete normal segments for a channel — checked before adding a new one. */
    @Query("SELECT COUNT(*) FROM dvr_segment WHERE isAlarmClip = 0 AND channel = :channel AND endMs > 0")
    suspend fun completeNormalCount(channel: Int): Int

    /** Alarm clips older than [beforeMs] that can be safely evicted. */
    @Query("SELECT * FROM dvr_segment WHERE isAlarmClip = 1 AND endMs > 0 AND endMs < :beforeMs ORDER BY startMs ASC")
    suspend fun getExpiredAlarmClips(beforeMs: Long): List<DvrSegmentEntity>

    /** All complete segments, ordered by age — used by StorageManager size eviction. */
    @Query("SELECT * FROM dvr_segment WHERE endMs > 0 AND isAlarmClip = 0 ORDER BY startMs ASC")
    suspend fun getNormalSegmentsForSizeEviction(): List<DvrSegmentEntity>

    @Query("SELECT SUM(fileSizeBytes) FROM dvr_segment WHERE endMs > 0")
    suspend fun totalSizeBytes(): Long

    @Query("SELECT * FROM dvr_segment ORDER BY startMs ASC")
    suspend fun getAll(): List<DvrSegmentEntity>
}
