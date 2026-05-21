package com.example.jt808terminal

import android.app.Application
import com.example.jt808terminal.core.TerminalRegistry

class TerminalApp : Application() {
    override fun onCreate() {
        super.onCreate()
        TerminalRegistry.init(this)
    }
}
