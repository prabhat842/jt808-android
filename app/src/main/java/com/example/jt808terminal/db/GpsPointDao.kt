package com.example.jt808terminal.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface GpsPointDao {

    @Insert
    suspend fun insert(point: GpsPointEntity): Long

    /** Oldest un-uploaded points, up to [limit] — used for 0x0704 batch construction. */
    @Query("SELECT * FROM gps_point_buffer WHERE uploaded = 0 ORDER BY timestampMs ASC LIMIT :limit")
    suspend fun getPending(limit: Int = 500): List<GpsPointEntity>

    @Query("SELECT COUNT(*) FROM gps_point_buffer WHERE uploaded = 0")
    suspend fun pendingCount(): Int

    @Query("UPDATE gps_point_buffer SET uploaded = 1 WHERE id IN (:ids)")
    suspend fun markUploaded(ids: List<Long>)

    /** Purge already-uploaded points older than [beforeMs] to cap table growth. */
    @Query("DELETE FROM gps_point_buffer WHERE uploaded = 1 AND timestampMs < :beforeMs")
    suspend fun deleteOldUploaded(beforeMs: Long)
}
