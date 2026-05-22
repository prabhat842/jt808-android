package com.example.jt808terminal.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.jt808terminal.R
import com.example.jt808terminal.service.TerminalService

class MainActivity : AppCompatActivity() {

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        refreshPermissionsRow()
        val allGranted = results.values.all { it }
        setServiceStatus(if (allGranted) "● Running" else "● Running (limited)", allGranted)
        startTerminalService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Hard-coded to match TerminalService.buildConfig() defaults
        findViewById<TextView>(R.id.tvServerAddr).text = "192.168.1.100 : 6808"
        findViewById<TextView>(R.id.tvRtvsAddr).text   = "192.168.1.100 : 6600"

        refreshPermissionsRow()
        requestPermissionsAndStart()
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

    private fun isGranted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private val REQUIRED_PERMISSIONS = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }
}
