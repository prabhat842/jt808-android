package com.example.jt808terminal.media

data class MediaSession(
    val terminalId: String,
    val channelId: Int,
    val streaming: Boolean = false,
    val talking: Boolean = false,
)
