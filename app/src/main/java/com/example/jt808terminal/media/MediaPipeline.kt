package com.example.jt808terminal.media

interface MediaPipeline {
    fun startVideo(channel: Int)
    fun stopVideo(channel: Int)
    fun startAudio(channel: Int)
    fun stopAudio(channel: Int)
}
