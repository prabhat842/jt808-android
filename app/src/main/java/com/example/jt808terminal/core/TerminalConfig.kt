package com.example.jt808terminal.core

data class TerminalConfig(
    // 12-digit SIM card number used in JT808 header BCD[6] — JT808-2013 §4.4.3 Table 2
    val phoneNumber: String,
    // 7-char device serial for registration body — JT808-2013 §8.5 Table 7
    val terminalId: String,
    val serverHost: String,
    val serverPort: Int,
    val rtvsHost: String,
    val rtvsPort: Int,
    val talkbackPort: Int = 6600,
    val manufacturerId: String = "GOATAI",
    val terminalModel: String = "AndroidV1",
    val plateColor: Int = 0,   // 0 = unregistered — JT808-2013 §8.5 Table 7
    val vin: String = "GOATAI0000000001",
)
