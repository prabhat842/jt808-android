package com.example.jt808terminal.core

import android.content.Context

object TerminalRegistry {
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun context(): Context = appContext
}
