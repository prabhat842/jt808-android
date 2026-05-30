package com.example.jt808terminal.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.jt808terminal.R
import com.example.jt808terminal.core.AppSettings
import com.example.jt808terminal.service.TerminalService

class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings
    private lateinit var etJt808Host: EditText
    private lateinit var etJt808Port: EditText
    private lateinit var etRtvsHost: EditText
    private lateinit var etRtvsPort: EditText
    private lateinit var etPhone: EditText
    private lateinit var etTerminalId: EditText
    private lateinit var etVin: EditText
    private lateinit var etDmsEar: EditText
    private lateinit var etDmsMar: EditText
    private lateinit var etDmsYaw: EditText
    private lateinit var etDmsPitch: EditText
    private lateinit var etDmsPerclosL1: EditText
    private lateinit var etDmsPerclosL2: EditText
    private lateinit var etDmsEyeBlink: EditText
    private lateinit var etDmsJaw: EditText
    private lateinit var tvError: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        settings = AppSettings(this)

        etJt808Host    = findViewById(R.id.etJt808Host)
        etJt808Port    = findViewById(R.id.etJt808Port)
        etRtvsHost     = findViewById(R.id.etRtvsHost)
        etRtvsPort     = findViewById(R.id.etRtvsPort)
        etPhone        = findViewById(R.id.etPhone)
        etTerminalId   = findViewById(R.id.etTerminalId)
        etVin          = findViewById(R.id.etVin)
        etDmsEar       = findViewById(R.id.etDmsEar)
        etDmsMar       = findViewById(R.id.etDmsMar)
        etDmsYaw       = findViewById(R.id.etDmsYaw)
        etDmsPitch     = findViewById(R.id.etDmsPitch)
        etDmsPerclosL1 = findViewById(R.id.etDmsPerclosL1)
        etDmsPerclosL2 = findViewById(R.id.etDmsPerclosL2)
        etDmsEyeBlink  = findViewById(R.id.etDmsEyeBlink)
        etDmsJaw       = findViewById(R.id.etDmsJaw)
        tvError        = findViewById(R.id.tvError)

        loadCurrent()

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btnSave).setOnClickListener { save() }
    }

    private fun loadCurrent() {
        etJt808Host.setText(settings.jt808Host)
        etJt808Port.setText(settings.jt808Port.toString())
        etRtvsHost.setText(settings.rtvsHost)
        etRtvsPort.setText(settings.rtvsPort.toString())
        etPhone.setText(settings.phoneNumber)
        etTerminalId.setText(settings.terminalId)
        etVin.setText(settings.vin)
        etDmsEar.setText("%.2f".format(settings.dmsEarClosed))
        etDmsMar.setText("%.2f".format(settings.dmsMarYawn))
        etDmsYaw.setText("%.2f".format(settings.dmsHeadYawRatio))
        etDmsPitch.setText("%.2f".format(settings.dmsHeadPitchRatio))
        etDmsPerclosL1.setText((settings.dmsPerclosL1 * 100).toInt().toString())
        etDmsPerclosL2.setText((settings.dmsPerclosL2 * 100).toInt().toString())
        etDmsEyeBlink.setText("%.2f".format(settings.dmsEyeBlinkThreshold))
        etDmsJaw.setText("%.2f".format(settings.dmsJawOpenThreshold))
    }

    private fun save() {
        val jt808Host  = etJt808Host.text.toString().trim()
        val jt808PortS = etJt808Port.text.toString().trim()
        val rtvsHost   = etRtvsHost.text.toString().trim()
        val rtvsPortS  = etRtvsPort.text.toString().trim()
        val phone      = etPhone.text.toString().trim()
        val termId     = etTerminalId.text.toString().trim()
        val vin        = etVin.text.toString().trim().uppercase()

        if (jt808Host.isBlank()) { showError("JT808 host is required"); return }
        if (rtvsHost.isBlank())  { showError("RTVS host is required"); return }
        if (phone.length != 12)  { showError("Phone number must be exactly 12 digits"); return }
        if (termId.isBlank())    { showError("Terminal ID is required"); return }
        if (vin.isBlank())       { showError("VIN is required"); return }

        val jt808Port = jt808PortS.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: run { showError("JT808 port must be 1–65535"); return }
        val rtvsPort  = rtvsPortS.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: run { showError("RTVS port must be 1–65535"); return }

        // Parse DMS thresholds — silently keep existing value if field is blank or invalid
        etDmsEar.text.toString().toFloatOrNull()?.let { settings.dmsEarClosed = it }
        etDmsMar.text.toString().toFloatOrNull()?.let { settings.dmsMarYawn = it }
        etDmsYaw.text.toString().toFloatOrNull()?.let { settings.dmsHeadYawRatio = it }
        etDmsPitch.text.toString().toFloatOrNull()?.let { settings.dmsHeadPitchRatio = it }
        etDmsEyeBlink.text.toString().toFloatOrNull()?.let { settings.dmsEyeBlinkThreshold = it }
        etDmsJaw.text.toString().toFloatOrNull()?.let { settings.dmsJawOpenThreshold = it }
        val l1 = etDmsPerclosL1.text.toString().toIntOrNull()
        val l2 = etDmsPerclosL2.text.toString().toIntOrNull()
        if (l1 != null && l2 != null && l1 >= 5 && l2 > l1 && l2 <= 95) {
            settings.dmsPerclosL1 = l1 / 100f
            settings.dmsPerclosL2 = l2 / 100f
        } else if (l1 != null || l2 != null) {
            showError("PERCLOS: Warning % must be < Danger %, both 5–95"); return
        }

        settings.jt808Host   = jt808Host
        settings.jt808Port   = jt808Port
        settings.rtvsHost    = rtvsHost
        settings.rtvsPort    = rtvsPort
        settings.phoneNumber = phone
        settings.terminalId  = termId
        settings.vin         = vin

        // Stop and restart the service so it picks up the new config
        stopService(Intent(this, TerminalService::class.java))
        startForegroundService(Intent(this, TerminalService::class.java))

        setResult(Activity.RESULT_OK)
        finish()
    }

    private fun showError(msg: String) {
        tvError.text = msg
        tvError.visibility = View.VISIBLE
    }
}
