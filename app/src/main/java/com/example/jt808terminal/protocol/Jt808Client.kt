package com.example.jt808terminal.protocol

interface Jt808Client {
    fun connect()
    fun disconnect()
    fun isConnected(): Boolean
}
