package com.example.jt808terminal.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Buffered GPS location point for offline 0x0704 batch upload.
 *
 * When the JT808 TCP connection is down, LocationReporter writes each 0x0200
 * report here instead of discarding it.  On reconnect, the pending points are
 * sent as a 0x0704 Positioning Data Batch Upload — JT808-2013 §8.49.
 *
 * [uploaded] is flipped to true after the 0x0704 batch containing this point
 * is ACKed by the platform.  Uploaded points older than 24 h are purged.
 */
@Entity(
    tableName = "gps_point_buffer",
    indices = [
        Index(value = ["uploaded", "timestampMs"]),
    ],
)
data class GpsPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val altitudeM: Int,
    val speedKph: Double,
    val headingDeg: Int,
    val alarmFlags: Long,
    val statusFlags: Long,
    val uploaded: Boolean = false,
)
