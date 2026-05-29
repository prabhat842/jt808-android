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
import com.example.jt808terminal.db.GpsPointDao
import com.example.jt808terminal.db.GpsPointEntity
import com.example.jt808terminal.dms.DmsAlarmState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Periodically sends 0x0200 location reports to the platform — JT808-2013 §7.3.1.
 *
 * Report interval: configurable via 0x8103 param 0x0029 (default 10 s).
 * GPS integration: Android LocationManager GPS_PROVIDER, updates every 1 s / 1 m.
 *
 * Offline GPS buffer — JT808-2013 §8.49 (0x0704):
 *   When the JT808 TCP connection is unavailable every location point is written to
 *   [gpsPointDao] instead of being discarded.  On each connected report cycle a batch
 *   of pending points (up to 500) is sent as a single 0x0704 message.  Points are
 *   marked uploaded after a successful send and purged after 24 h.
 */
class LocationReporter(
    private val context: Context,
    private val client: Jt808TcpClientImpl,
    private val scope: CoroutineScope,
    @Volatile var intervalMs: Long = 10_000L,
    private val dmsAlarmState: DmsAlarmState? = null,
    private val adasAlarmState: AdasAlarmState? = null,
    private val bsdAlarmState: BsdAlarmState? = null,
    private val gpsPointDao: GpsPointDao? = null,
) {
    @Volatile private var latest: Location? = null
    @Volatile private var previousAlarmWord: Long = 0L

    /**
     * Invoked (on the coroutine scope) when a JT808 alarm bit rises for the first time.
     * Fired after the 0x0200 location report is queued so the server sees the alarm before
     * the media upload arrives — mirrors TerminalSession.sendLocation in the simulator.
     *
     * Parameters: bitIndex, alarmFlags (full word at rise time), statusFlags, speedKph, latDeg, lonDeg
     */
    var onAlarmRising: ((bitIndex: Int, alarmFlags: Long, statusFlags: Long,
                         speedKph: Double, latDeg: Double, lonDeg: Double) -> Unit)? = null

    /** Returns the most recent GPS fix, or null when no fix has been received yet. */
    fun latestLocation(): Location? = latest

    private val locationListener = LocationListener { loc ->
        latest = loc
        // Update speed immediately on every GPS fix (1 s interval) so the UI overlay
        // always shows fresh speed rather than waiting for the 10-second report cycle.
        val kph = loc.speed * 3.6f
        dmsAlarmState?.currentSpeedKph  = kph
        adasAlarmState?.currentSpeedKph = kph
    }

    @SuppressLint("MissingPermission")
    fun start() {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1_000L,
                1f,
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

        // Periodically drain the offline GPS buffer when connected — 0x0704 batch upload.
        if (gpsPointDao != null) {
            scope.launch {
                while (isActive) {
                    delay(30_000L)   // check every 30 s
                    flushOfflineBuffer()
                    pruneUploaded()
                }
            }
        }
    }

    /** Sends an immediate 0x0200 outside the normal cycle — called on DMS/ADAS danger alarm. */
    fun reportNow() {
        scope.launch { report() }
    }

    /** Immediately flushes the offline GPS buffer — called when authentication is restored. */
    fun flushNow() {
        if (gpsPointDao != null) scope.launch(Dispatchers.IO) { flushOfflineBuffer() }
    }

    // -------------------------------------------------------------------------

    private suspend fun report() {
        val loc = latest
        val now = System.currentTimeMillis()

        var statusFlags = 0x01L
        var latDeg = 0.0; var lonDeg = 0.0; var altM = 0
        var speedKph = 0.0; var heading = 0; var satellites = 0

        if (loc != null) {
            statusFlags = statusFlags or 0x02L
            if (loc.latitude  < 0) statusFlags = statusFlags or 0x04L
            if (loc.longitude < 0) statusFlags = statusFlags or 0x08L
            if (loc.speed * 3.6 < 1.0) statusFlags = statusFlags or 0x10L
            statusFlags = statusFlags or 0x40000L
            latDeg = loc.latitude; lonDeg = loc.longitude
            altM = loc.altitude.toInt()
            speedKph = loc.speed * 3.6
            heading = loc.bearing.toInt()
            satellites = loc.extras?.getInt("satellites", 0) ?: 0
        }

        dmsAlarmState?.currentSpeedKph  = speedKph.toFloat()
        adasAlarmState?.currentSpeedKph = speedKph.toFloat()

        var alarmFlags = 0L
        var extraItems = ByteArray(0)

        val dms = dmsAlarmState
        if (dms != null) {
            val flags = dms.behaviourFlags; val deg = dms.fatigueDegree
            if ((flags and 0x01) != 0) alarmFlags = alarmFlags or (1L shl 14)
            if (flags != 0 || deg > 0) extraItems = Jt808Messages.additionalDrivingBehavior(flags, deg)
        }
        val adas = adasAlarmState
        if (adas != null) {
            if (adas.overSpeedAlarm)                       alarmFlags = alarmFlags or (1L shl 1)
            if (adas.pedestrianRisk || adas.ldwActive)     alarmFlags = alarmFlags or (1L shl 3)
            if (adas.overSpeedWarning)                     alarmFlags = alarmFlags or (1L shl 13)
            if (adas.fcwActive)                            alarmFlags = alarmFlags or (1L shl 29)
        }
        if (bsdAlarmState?.hasAlarm() == true)             alarmFlags = alarmFlags or (1L shl 29)

        val body = Jt808Messages.locationReport(
            alarmFlags = alarmFlags, statusFlags = statusFlags,
            latitudeDeg = latDeg, longitudeDeg = lonDeg,
            altitudeM = altM, speedKph = speedKph, headingDeg = heading,
            timestampMs = now, signalStrength = 0, satelliteCount = satellites,
            extraItems = extraItems,
        )

        if (client.isAuthenticated()) {
            client.sendLocationReport(body)
            Log.v(TAG, "0x0200 sent lat=$latDeg lon=$lonDeg speed=${"%.1f".format(speedKph)}km/h alarm=0x${alarmFlags.toString(16)}")
        } else if (loc != null && gpsPointDao != null) {
            // Offline and have a real GPS fix — buffer for 0x0704 batch upload on reconnect.
            // Skip buffering when loc==null (no fix) to avoid storing useless zero-coordinate rows.
            scope.launch(Dispatchers.IO) {
                gpsPointDao.insert(GpsPointEntity(
                    timestampMs  = now,
                    latitudeDeg  = latDeg,
                    longitudeDeg = lonDeg,
                    altitudeM    = altM,
                    speedKph     = speedKph,
                    headingDeg   = heading,
                    alarmFlags   = alarmFlags,
                    statusFlags  = statusFlags,
                ))
                Log.d(TAG, "Offline: GPS point buffered (${gpsPointDao.pendingCount()} pending)")
            }
        }

        // Rising-edge detection — fire autonomous media upload for each newly set alarm bit.
        // Mirrors TerminalSession.sendLocation rising-bit loop in the simulator.
        val risingBits = alarmFlags and previousAlarmWord.inv()
        if (risingBits != 0L) {
            val cb = onAlarmRising
            if (cb != null) {
                for (bit in 0..31) {
                    if ((risingBits and (1L shl bit)) != 0L) {
                        cb.invoke(bit, alarmFlags, statusFlags, speedKph, latDeg, lonDeg)
                    }
                }
            }
        }
        previousAlarmWord = alarmFlags
    }

    /**
     * Sends pending GPS points as a 0x0704 Positioning Data Batch Upload.
     * JT808-2013 §8.49: body = count(WORD) + dataType(BYTE) + list of location reports.
     *
     * Each location record in the list is a standard 0x0200 body (28-byte base + extras).
     * We send video-less records with no extras to keep packets manageable.
     */
    private suspend fun flushOfflineBuffer() {
        val dao = gpsPointDao ?: return
        if (!client.isAuthenticated()) return
        val pending = dao.getPending(limit = 500)
        if (pending.isEmpty()) return

        val items = ArrayList<ByteArray>(pending.size)
        for (p in pending) {
            val body = Jt808Messages.locationReport(
                alarmFlags   = p.alarmFlags,
                statusFlags  = p.statusFlags,
                latitudeDeg  = p.latitudeDeg,
                longitudeDeg = p.longitudeDeg,
                altitudeM    = p.altitudeM,
                speedKph     = p.speedKph,
                headingDeg   = p.headingDeg,
                timestampMs  = p.timestampMs,
                signalStrength  = 0,
                satelliteCount  = 0,
                extraItems      = ByteArray(0),
            )
            items.add(body)
        }

        val batch = buildBatchBody(items)
        client.sendCommand(MsgId.LOCATION_BATCH_UPLOAD, batch)
        Log.i(TAG, "0x0704 sent ${pending.size} buffered GPS point(s)")

        val ids = pending.map { it.id }
        scope.launch(Dispatchers.IO) { dao.markUploaded(ids) }
    }

    private fun buildBatchBody(reports: List<ByteArray>): ByteArray {
        // 0x0704 body — JT808-2013 §8.49:
        //   [0-1] count  WORD
        //   [2]   dataType BYTE  0=normal location
        //   [3+]  items: each prefixed with WORD length
        val out = ArrayList<Byte>(3 + reports.sumOf { it.size + 2 })
        val count = reports.size
        out.add((count shr 8).toByte()); out.add((count and 0xFF).toByte())
        out.add(0)   // dataType = 0 (normal location report)
        for (r in reports) {
            out.add((r.size shr 8).toByte()); out.add((r.size and 0xFF).toByte())
            for (b in r) out.add(b)
        }
        return out.toByteArray()
    }

    private suspend fun pruneUploaded() {
        val dao = gpsPointDao ?: return
        val cutoff = System.currentTimeMillis() - 24L * 3600 * 1000
        scope.launch(Dispatchers.IO) { dao.deleteOldUploaded(cutoff) }
    }

    companion object {
        private const val TAG = "LocationReporter"
    }
}
