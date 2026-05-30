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

    // --- Vehicle registration fields (sent in 0x0100) ---

    /** 17-char VIN — JT808-2013 §8.5 Table 7: used as license plate when plateColor=0. */
    var vin: String
        get() = prefs.getString(KEY_VIN, "GOATAI0000000001") ?: "GOATAI0000000001"
        set(v) { prefs.edit().putString(KEY_VIN, v.trim().uppercase()).apply() }

    /** License plate color per JT/T 415-2006: 0=unregistered, 1=blue, 2=yellow, 3=black, 4=white */
    var plateColor: Int
        get() = prefs.getInt(KEY_PLATE_COLOR, 0)
        set(v) { prefs.edit().putInt(KEY_PLATE_COLOR, v.coerceIn(0, 9)).apply() }

    /** 5-char manufacturer ID sent in 0x0100 registration. */
    var manufacturerId: String
        get() = prefs.getString(KEY_MANUFACTURER_ID, "GOATA") ?: "GOATA"
        set(v) { prefs.edit().putString(KEY_MANUFACTURER_ID, v.trim().uppercase().take(5)).apply() }

    /** Up-to-20-char terminal model string sent in 0x0100 registration. */
    var terminalModel: String
        get() = prefs.getString(KEY_TERMINAL_MODEL, "AndroidV1") ?: "AndroidV1"
        set(v) { prefs.edit().putString(KEY_TERMINAL_MODEL, v.trim().take(20)).apply() }

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

    /** DMS voice alerts enabled — user can mute during testing. Persists across restarts. */
    var dmsAudioEnabled: Boolean
        get() = prefs.getBoolean(KEY_DMS_AUDIO, true)
        set(v) { prefs.edit().putBoolean(KEY_DMS_AUDIO, v).apply() }

    // ── DMS detection thresholds (tuned via Settings screen) ──────────────────

    /** EAR below this → eyes closed. Lower = more sensitive. Default 0.20. */
    var dmsEarClosed: Float
        get() = prefs.getFloat(KEY_DMS_EAR, 0.20f)
        set(v) { prefs.edit().putFloat(KEY_DMS_EAR, v.coerceIn(0.05f, 0.50f)).apply() }

    /** MAR above this → yawning. Lower = more sensitive. Default 0.50. */
    var dmsMarYawn: Float
        get() = prefs.getFloat(KEY_DMS_MAR, 0.50f)
        set(v) { prefs.edit().putFloat(KEY_DMS_MAR, v.coerceIn(0.10f, 0.90f)).apply() }

    /** Nose-offset ratio for head yaw distraction. Lower = more sensitive. Default 0.25. */
    var dmsHeadYawRatio: Float
        get() = prefs.getFloat(KEY_DMS_YAW, 0.25f)
        set(v) { prefs.edit().putFloat(KEY_DMS_YAW, v.coerceIn(0.05f, 0.80f)).apply() }

    /** Nose-offset ratio for head pitch distraction. Lower = more sensitive. Default 0.15. */
    var dmsHeadPitchRatio: Float
        get() = prefs.getFloat(KEY_DMS_PITCH, 0.15f)
        set(v) { prefs.edit().putFloat(KEY_DMS_PITCH, v.coerceIn(0.05f, 0.60f)).apply() }

    /** eyeBlinkLeft/Right blend-shape score above which eyes are closed. Default 0.50. */
    var dmsEyeBlinkThreshold: Float
        get() = prefs.getFloat(KEY_DMS_BLINK, 0.50f)
        set(v) { prefs.edit().putFloat(KEY_DMS_BLINK, v.coerceIn(0.10f, 0.90f)).apply() }

    /** jawOpen blend-shape score above which yawning is detected. Default 0.60. */
    var dmsJawOpenThreshold: Float
        get() = prefs.getFloat(KEY_DMS_JAW, 0.60f)
        set(v) { prefs.edit().putFloat(KEY_DMS_JAW, v.coerceIn(0.10f, 0.95f)).apply() }

    /** PERCLOS fraction for level-1 fatigue warning. Default 0.35 (35 %). */
    var dmsPerclosL1: Float
        get() = prefs.getFloat(KEY_DMS_PERCLOS_L1, 0.35f)
        set(v) { prefs.edit().putFloat(KEY_DMS_PERCLOS_L1, v.coerceIn(0.05f, 0.95f)).apply() }

    /** PERCLOS fraction for level-2 fatigue danger. Default 0.50 (50 %). */
    var dmsPerclosL2: Float
        get() = prefs.getFloat(KEY_DMS_PERCLOS_L2, 0.50f)
        set(v) { prefs.edit().putFloat(KEY_DMS_PERCLOS_L2, v.coerceIn(0.05f, 0.95f)).apply() }

    fun isConfigured() = jt808Host.isNotBlank() && rtvsHost.isNotBlank()

    companion object {
        private const val KEY_JT808_HOST          = "jt808_host"
        private const val KEY_JT808_PORT          = "jt808_port"
        private const val KEY_RTVS_HOST           = "rtvs_host"
        private const val KEY_RTVS_PORT           = "rtvs_port"
        private const val KEY_PHONE               = "phone_number"
        private const val KEY_TERMINAL_ID         = "terminal_id"
        private const val KEY_VIN                 = "vin"
        private const val KEY_PLATE_COLOR         = "plate_color"
        private const val KEY_MANUFACTURER_ID     = "manufacturer_id"
        private const val KEY_TERMINAL_MODEL      = "terminal_model"
        private const val KEY_HEARTBEAT_INTERVAL  = "heartbeat_interval_sec"
        private const val KEY_LOCATION_INTERVAL   = "location_interval_sec"
        private const val KEY_OVERSPEED_ALARM_KPH = "overspeed_alarm_kph"
        private const val KEY_OVERSPEED_GAP       = "overspeed_gap_tenth_kph"
        private const val KEY_MAX_STORAGE_MB      = "max_storage_mb"
        private const val KEY_DMS_AUDIO           = "dms_audio_enabled"
        private const val KEY_DMS_EAR             = "dms_ear_closed"
        private const val KEY_DMS_MAR             = "dms_mar_yawn"
        private const val KEY_DMS_YAW             = "dms_head_yaw_ratio"
        private const val KEY_DMS_PITCH           = "dms_head_pitch_ratio"
        private const val KEY_DMS_PERCLOS_L1      = "dms_perclos_l1"
        private const val KEY_DMS_PERCLOS_L2      = "dms_perclos_l2"
        private const val KEY_DMS_BLINK           = "dms_eye_blink_threshold"
        private const val KEY_DMS_JAW             = "dms_jaw_open_threshold"
    }
}
