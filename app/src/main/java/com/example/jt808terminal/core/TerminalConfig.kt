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
    // 0x0100 registration fields — all sourced from AppSettings, not hardcoded
    val manufacturerId: String,   // 5-char, BYTE[5] in Table 7
    val terminalModel: String,    // up to 20 chars, BYTE[20] in Table 7
    val plateColor: Int,          // 0=unregistered per JT/T 415-2006
    val vin: String,              // used as plate number when plateColor=0
)
