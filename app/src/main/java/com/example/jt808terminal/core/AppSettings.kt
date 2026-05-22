package com.example.jt808terminal.core

import android.content.Context

class AppSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("jt808_prefs", Context.MODE_PRIVATE)

    var jt808Host: String
        get() = prefs.getString(KEY_JT808_HOST, "") ?: ""
        set(v) { prefs.edit().putString(KEY_JT808_HOST, v.trim()).apply() }

    var jt808Port: Int
        get() = prefs.getInt(KEY_JT808_PORT, 6808)
        set(v) { prefs.edit().putInt(KEY_JT808_PORT, v).apply() }

    var rtvsHost: String
        get() = prefs.getString(KEY_RTVS_HOST, "") ?: ""
        set(v) { prefs.edit().putString(KEY_RTVS_HOST, v.trim()).apply() }

    var rtvsPort: Int
        get() = prefs.getInt(KEY_RTVS_PORT, 6600)
        set(v) { prefs.edit().putInt(KEY_RTVS_PORT, v).apply() }

    var phoneNumber: String
        get() = prefs.getString(KEY_PHONE, "000000000001") ?: "000000000001"
        set(v) { prefs.edit().putString(KEY_PHONE, v.trim()).apply() }

    var terminalId: String
        get() = prefs.getString(KEY_TERMINAL_ID, "GOATAI01") ?: "GOATAI01"
        set(v) { prefs.edit().putString(KEY_TERMINAL_ID, v.trim()).apply() }

    // --- Server-configured parameters (updated via 0x8103) ---

    /** 0x8103 param 0x0001 — Terminal heartbeat interval (seconds). */
    var heartbeatIntervalSec: Int
        get() = prefs.getInt(KEY_HEARTBEAT_INTERVAL, 30)
        set(v) { prefs.edit().putInt(KEY_HEARTBEAT_INTERVAL, v.coerceAtLeast(5)).apply() }

    /** 0x8103 param 0x0029 — Location report interval (seconds). */
    var locationReportIntervalSec: Int
        get() = prefs.getInt(KEY_LOCATION_INTERVAL, 10)
        set(v) { prefs.edit().putInt(KEY_LOCATION_INTERVAL, v.coerceAtLeast(1)).apply() }

    /** 0x8103 param 0x0055 — Overspeed alarm threshold (km/h). */
    var overSpeedAlarmKph: Int
        get() = prefs.getInt(KEY_OVERSPEED_ALARM_KPH, 100)
        set(v) { prefs.edit().putInt(KEY_OVERSPEED_ALARM_KPH, v.coerceAtLeast(1)).apply() }

    /** 0x8103 param 0x005B — Gap between alarm and warning speeds (1/10 km/h). */
    var overSpeedWarningGapTenthKph: Int
        get() = prefs.getInt(KEY_OVERSPEED_GAP, 200)   // default gap = 20 km/h
        set(v) { prefs.edit().putInt(KEY_OVERSPEED_GAP, v.coerceAtLeast(0)).apply() }

    /** Maximum local recording storage (MB) before eviction. */
    var maxStorageMb: Int
        get() = prefs.getInt(KEY_MAX_STORAGE_MB, 2048)
        set(v) { prefs.edit().putInt(KEY_MAX_STORAGE_MB, v.coerceAtLeast(128)).apply() }

    fun isConfigured() = jt808Host.isNotBlank() && rtvsHost.isNotBlank()

    companion object {
        private const val KEY_JT808_HOST          = "jt808_host"
        private const val KEY_JT808_PORT          = "jt808_port"
        private const val KEY_RTVS_HOST           = "rtvs_host"
        private const val KEY_RTVS_PORT           = "rtvs_port"
        private const val KEY_PHONE               = "phone_number"
        private const val KEY_TERMINAL_ID         = "terminal_id"
        private const val KEY_HEARTBEAT_INTERVAL  = "heartbeat_interval_sec"
        private const val KEY_LOCATION_INTERVAL   = "location_interval_sec"
        private const val KEY_OVERSPEED_ALARM_KPH = "overspeed_alarm_kph"
        private const val KEY_OVERSPEED_GAP       = "overspeed_gap_tenth_kph"
        private const val KEY_MAX_STORAGE_MB      = "max_storage_mb"
    }
}
