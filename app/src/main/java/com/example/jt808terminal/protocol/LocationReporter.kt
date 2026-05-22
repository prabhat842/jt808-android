package com.example.jt808terminal.protocol

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import com.example.jt808terminal.adas.AdasAlarmState
import com.example.jt808terminal.bsd.BsdAlarmState
import com.example.jt808terminal.dms.DmsAlarmState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Periodically sends 0x0200 location reports to the platform — JT808-2013 §7.3.1.
 *
 * Report interval: 10 seconds (spec §4.2.2 recommendation; configurable via 0x8103 param 0x0029).
 * GPS integration: Android LocationManager GPS_PROVIDER, updates every 1 s / 1 m.
 *
 * Status word bits — JT808-2013 §8.18 Table 25:
 *   bit 0 = ACC on (1)
 *   bit 1 = positioning (1 when fix)
 *   bit 2 = South latitude (1 when lat < 0)
 *   bit 3 = West longitude (1 when lon < 0)
 *   bit 4 = stop running (0 = moving)
 *   bit 18 = GPS positioning (1 when GPS fix)
 */
class LocationReporter(
    private val context: Context,
    private val client: Jt808TcpClientImpl,
    private val scope: CoroutineScope,
    private val intervalMs: Long = 10_000L,
    private val dmsAlarmState: DmsAlarmState? = null,
    private val adasAlarmState: AdasAlarmState? = null,
    private val bsdAlarmState: BsdAlarmState? = null,
) {
    @Volatile private var latest: Location? = null

    private val locationListener = LocationListener { loc -> latest = loc }

    @SuppressLint("MissingPermission")
    fun start() {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1_000L,   // minTime ms
                1f,       // minDistance m
                locationListener,
                Looper.getMainLooper(),
            )
            Log.i(TAG, "GPS updates requested")
        } catch (e: Exception) {
            Log.w(TAG, "GPS unavailable: ${e.message}")
        }

        scope.launch {
            while (isActive) {
                delay(intervalMs)
                report()
            }
        }
    }

    /** Sends an immediate 0x0200 outside the normal 10-s cycle — called on DMS danger alarm. */
    fun reportNow() {
        scope.launch { report() }
    }

    private fun report() {
        val loc = latest
        val now = System.currentTimeMillis()

        // Build status word — JT808-2013 §8.18 Table 25
        var statusFlags = 0x01L  // bit 0: ACC on
        var latDeg = 0.0
        var lonDeg = 0.0
        var altM = 0
        var speedKph = 0.0
        var heading = 0
        var satellites = 0

        if (loc != null) {
            statusFlags = statusFlags or 0x02L  // bit 1: positioning
            if (loc.latitude < 0) statusFlags = statusFlags or 0x04L   // bit 2: south
            if (loc.longitude < 0) statusFlags = statusFlags or 0x08L  // bit 3: west
            if (loc.speed * 3.6 < 1.0) statusFlags = statusFlags or 0x10L  // bit 4: stopped
            statusFlags = statusFlags or 0x40000L  // bit 18: GPS positioning
            latDeg = loc.latitude
            lonDeg = loc.longitude
            altM = loc.altitude.toInt()
            speedKph = loc.speed * 3.6   // m/s → km/h
            heading = loc.bearing.toInt()
            if (loc.extras != null) {
                satellites = loc.extras?.getInt("satellites", 0) ?: 0
            }
        }

        // Share current speed with DmsEngine and AdasEngine for speed-gated checks.
        dmsAlarmState?.currentSpeedKph  = speedKph.toFloat()
        adasAlarmState?.currentSpeedKph = speedKph.toFloat()

        // Alarm sign bits — JT808-2013 §8.18 Table 24
        var alarmFlags = 0L
        var extraItems = ByteArray(0)

        // DMS alarms
        val dms = dmsAlarmState
        if (dms != null) {
            val flags = dms.behaviourFlags
            val deg   = dms.fatigueDegree
            // bit 14: Fatigue driving warning
            if ((flags and 0x01) != 0) alarmFlags = alarmFlags or (1L shl 14)
            if (flags != 0 || deg > 0) {
                // Additional info 0x18: driving behaviour — JT808-2013 Table 27 / JT1078 extension
                extraItems = Jt808Messages.additionalDrivingBehavior(flags, deg)
            }
        }

        // ADAS alarms — JT808-2013 Table 24
        val adas = adasAlarmState
        if (adas != null) {
            if (adas.overSpeedAlarm)   alarmFlags = alarmFlags or (1L shl 1)   // bit 1: Over speed alarm
            if (adas.pedestrianRisk || adas.ldwActive)
                                       alarmFlags = alarmFlags or (1L shl 3)   // bit 3: Risk warning
            if (adas.overSpeedWarning) alarmFlags = alarmFlags or (1L shl 13)  // bit 13: Over speed warning
            if (adas.fcwActive)        alarmFlags = alarmFlags or (1L shl 29)  // bit 29: Collision warning
        }

        // BSD alarms — JT808-2013 Table 24 bit 29 (Collision warning)
        val bsd = bsdAlarmState
        if (bsd != null && bsd.hasAlarm()) {
            alarmFlags = alarmFlags or (1L shl 29)  // bit 29: Collision warning
        }

        val body = Jt808Messages.locationReport(
            alarmFlags = alarmFlags,
            statusFlags = statusFlags,
            latitudeDeg = latDeg,
            longitudeDeg = lonDeg,
            altitudeM = altM,
            speedKph = speedKph,
            headingDeg = heading,
            timestampMs = now,
            signalStrength = 0,
            satelliteCount = satellites,
            extraItems = extraItems,
        )

        client.sendLocationReport(body)
        Log.v(TAG, "0x0200 sent lat=$latDeg lon=$lonDeg speed=${"%.1f".format(speedKph)}km/h alarm=0x${alarmFlags.toString(16)}")
    }

    companion object {
        private const val TAG = "LocationReporter"
    }
}
