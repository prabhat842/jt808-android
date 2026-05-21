package com.example.jt808terminal.ui

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.jt808terminal.service.TerminalService

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startService(Intent(this, TerminalService::class.java))
        setContentView(TextView(this).apply {
            text = "JT808 terminal scaffold"
            textSize = 20f
            setPadding(48, 48, 48, 48)
        })
    }
}
