package com.example.jt808terminal.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.jt808terminal.service.TerminalService

class MainActivity : AppCompatActivity() {

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filterValues { !it }.keys
        if (denied.isEmpty()) startTerminalService()
        // If some permissions denied, service still starts — it handles missing perms gracefully
        else startTerminalService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "JT808 Terminal — running"
            textSize = 20f
            setPadding(48, 48, 48, 48)
        })
        requestPermissionsAndStart()
    }

    private fun requestPermissionsAndStart() {
        val needed = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            startTerminalService()
        } else {
            permissionsLauncher.launch(needed.toTypedArray())
        }
    }

    private fun startTerminalService() {
        startForegroundService(Intent(this, TerminalService::class.java))
    }

    companion object {
        private val REQUIRED_PERMISSIONS = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }
}
