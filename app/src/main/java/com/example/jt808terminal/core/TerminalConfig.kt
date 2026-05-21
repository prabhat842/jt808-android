package com.example.jt808terminal.core

data class TerminalConfig(
    val terminalId: String,
    val serverHost: String,
    val serverPort: Int,
    val rtvsHost: String,
    val rtvsPort: Int,
    val talkbackPort: Int,
)
