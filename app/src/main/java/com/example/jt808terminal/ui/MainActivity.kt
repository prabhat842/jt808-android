package com.example.jt808terminal.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.jt808terminal.R
import com.example.jt808terminal.core.AppSettings
import com.example.jt808terminal.service.TerminalService

class MainActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings

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

        refreshServerDisplay()
        refreshPermissionsRow()
        requestPermissionsAndStart()
    }

    override fun onResume() {
        super.onResume()
        refreshServerDisplay()
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
