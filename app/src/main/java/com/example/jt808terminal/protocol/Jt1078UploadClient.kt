package com.example.jt808terminal.protocol

interface Jt1078UploadClient {
    fun startVideoUpload(channel: Int)
    fun stopVideoUpload(channel: Int)
    fun startTalkback(channel: Int)
    fun stopTalkback(channel: Int)
}
