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

    fun isConfigured() = jt808Host.isNotBlank() && rtvsHost.isNotBlank()

    companion object {
        private const val KEY_JT808_HOST   = "jt808_host"
        private const val KEY_JT808_PORT   = "jt808_port"
        private const val KEY_RTVS_HOST    = "rtvs_host"
        private const val KEY_RTVS_PORT    = "rtvs_port"
        private const val KEY_PHONE        = "phone_number"
        private const val KEY_TERMINAL_ID  = "terminal_id"
    }
}
