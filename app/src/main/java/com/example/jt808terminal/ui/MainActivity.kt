package com.example.jt808terminal.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale as JavaLocale
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.example.jt808terminal.R
import com.example.jt808terminal.core.AppSettings
import com.example.jt808terminal.service.TerminalService

class MainActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings
    private val uiHandler = Handler(Looper.getMainLooper())
    private val dtFormatter = SimpleDateFormat("dd MMM  HH:mm:ss", JavaLocale.US)
    private val dmsPoller = object : Runnable {
        override fun run() {
            updateDmsPanel()
            updateSpeedTime()
            uiHandler.postDelayed(this, 500)
        }
    }

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        refreshPermissionsRow()
        val allGranted = results.values.all { it }
        setServiceStatus("● Running", allGranted)
        startTerminalService()
    }

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // Service was already restarted inside SettingsActivity — just refresh the display
            refreshServerDisplay()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = AppSettings(this)

        findViewById<TextView>(R.id.btnSettings).setOnClickListener {
            settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
        }

        findViewById<TextView>(R.id.btnMuteAudio).setOnClickListener {
            val enabled = TerminalService.toggleAudio(this)
            updateMuteButton(enabled)
        }

        refreshServerDisplay()
        refreshPermissionsRow()
        requestPermissionsAndStart()
    }

    override fun onResume() {
        super.onResume()
        TerminalService.isAppInForeground = true
        TerminalService.setLocalPreview(findViewById<PreviewView>(R.id.previewView).surfaceProvider)
        updateMuteButton(TerminalService.isAudioEnabled(this))
        refreshServerDisplay()
        uiHandler.post(dmsPoller)
    }

    override fun onPause() {
        super.onPause()
        TerminalService.isAppInForeground = false
        TerminalService.pauseCameras()
        uiHandler.removeCallbacks(dmsPoller)
    }

    // Called when user explicitly leaves (Home / Recents) — not when launching SettingsActivity.
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        finish()
    }

    private fun refreshServerDisplay() {
        val jt808Addr = if (settings.jt808Host.isBlank()) "—"
            else "${settings.jt808Host} : ${settings.jt808Port}"
        val rtvsAddr  = if (settings.rtvsHost.isBlank()) "—"
            else "${settings.rtvsHost} : ${settings.rtvsPort}"

        findViewById<TextView>(R.id.tvServerAddr).text = jt808Addr
        findViewById<TextView>(R.id.tvRtvsAddr).text   = rtvsAddr

        val notConfigured = !settings.isConfigured()
        findViewById<LinearLayout>(R.id.cardNotConfigured).visibility =
            if (notConfigured) View.VISIBLE else View.GONE
    }

    private fun requestPermissionsAndStart() {
        val needed = REQUIRED_PERMISSIONS.filter { !isGranted(it) }
        if (needed.isEmpty()) {
            setServiceStatus("● Running", true)
            startTerminalService()
        } else {
            permissionsLauncher.launch(needed.toTypedArray())
        }
    }

    private fun refreshPermissionsRow() {
        val lines = REQUIRED_PERMISSIONS.joinToString("\n") { perm ->
            val label = perm.substringAfterLast('.').lowercase().replace('_', ' ')
                .replaceFirstChar { it.uppercase() }
            if (isGranted(perm)) "✓  $label" else "✗  $label"
        }
        findViewById<TextView>(R.id.tvPermissions).text = lines
    }

    private fun setServiceStatus(text: String, ok: Boolean) {
        val tv = findViewById<TextView>(R.id.tvServiceStatus)
        tv.text = text
        tv.setTextColor(if (ok) 0xFF4CAF50.toInt() else 0xFFFF9800.toInt())
    }

    private fun updateSpeedTime() {
        val speed = TerminalService.getDmsAlarmState()?.currentSpeedKph ?: 0f
        val dt = dtFormatter.format(Date())
        findViewById<TextView>(R.id.tvSpeedTime).text =
            "${speed.toInt()} km/h\n$dt"
    }

    private fun updateMuteButton(audioEnabled: Boolean) {
        findViewById<TextView>(R.id.btnMuteAudio).text = if (audioEnabled) "🔊" else "🔇"
    }

    private fun updateDmsPanel() {
        val overlay = findViewById<TextView>(R.id.tvDmsOverlay)
        val s = TerminalService.getDmsAlarmState()

        val alerts = mutableListOf<String>()
        if (s != null) {
            if (!s.faceDetected)  alerts += "⚠ Face Missing"
            if (s.eyesClosed)     alerts += "⚠ Eyes Closed"
            if (s.isYawning)      alerts += "⚠ Yawning (${s.yawnCount}x)"
            if (s.headDistracted) alerts += "⚠ Head Distracted"
            if (s.alarmLevel >= 2) alerts += "🚨 Fatigue Danger (${s.fatigueDegree}%)"
            else if (s.alarmLevel == 1) alerts += "⚠ Fatigue Warning (${s.fatigueDegree}%)"
        }

        if (alerts.isEmpty()) {
            overlay.visibility = View.GONE
        } else {
            overlay.text = alerts.joinToString("   ")
            overlay.setTextColor(
                if (s?.alarmLevel ?: 0 >= 2) Color.parseColor("#FF5252")
                else Color.parseColor("#FFC107")
            )
            overlay.visibility = View.VISIBLE
        }
    }

    private fun startTerminalService() {
        startForegroundService(Intent(this, TerminalService::class.java))
    }

    private fun isGranted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    companion object {
        private val REQUIRED_PERMISSIONS = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }
}
