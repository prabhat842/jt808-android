package com.example.jt808terminal.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Single Room database for the terminal.
 *
 * Tables:
 *   dvr_segment       — local DVR recording index; replaces the JSON RecordingIndex
 *   gps_point_buffer  — offline GPS buffer for 0x0704 batch upload on reconnect
 */
@Database(
    entities = [DvrSegmentEntity::class, GpsPointEntity::class],
    version  = 1,
    exportSchema = false,
)
abstract class TerminalDatabase : RoomDatabase() {

    abstract fun dvrSegmentDao(): DvrSegmentDao
    abstract fun gpsPointDao(): GpsPointDao

    companion object {
        @Volatile private var instance: TerminalDatabase? = null

        fun getInstance(context: Context): TerminalDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    TerminalDatabase::class.java,
                    "terminal.db",
                ).build().also { instance = it }
            }
    }
}
