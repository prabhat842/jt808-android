package com.example.jt808terminal.protocol

import android.util.Log
import com.example.jt808terminal.core.TerminalConfig
import com.example.jt808terminal.protocol.Jt808Codec.DecodedFrame
import com.example.jt808terminal.protocol.MsgId.HEARTBEAT
import com.example.jt808terminal.protocol.MsgId.LOCATION_REPORT
import com.example.jt808terminal.protocol.MsgId.PLATFORM_GENERAL_RESPONSE
import com.example.jt808terminal.protocol.MsgId.REGISTRATION_RESPONSE
import com.example.jt808terminal.protocol.MsgId.TERMINAL_AUTH
import com.example.jt808terminal.protocol.MsgId.TERMINAL_GENERAL_RESPONSE
import com.example.jt808terminal.protocol.MsgId.TERMINAL_REGISTER
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * JT808-2013 terminal TCP client.
 *
 * Lifecycle (§5.1, §7.2.1, §7.2.2):
 *   1. TCP connect to platform server
 *   2. Send 0x0100 registration
 *   3. Platform replies 0x8100 — extract auth token
 *   4. Send 0x0102 authentication
 *   5. Platform replies 0x8001 result=0 → authenticated
 *   6. Heartbeat every 30 s (§5.2)
 *   Auto-reconnect on disconnect with exponential back-off (§5.3).
 */
class Jt808TcpClientImpl(
    private val config: TerminalConfig,
    private val scope: CoroutineScope,
) : Jt808Client {

    @Volatile private var socket: Socket? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var authToken: String = ""
    @Volatile private var authenticated = false

    private val seqGen = AtomicInteger(0)

    /**
     * Configurable heartbeat interval — JT808-2013 §8.9 Table 12 param 0x0001 (seconds).
     * Updated live by 0x8103 handler; next heartbeat fires after current delay completes.
     */
    @Volatile var heartbeatIntervalMs: Long = 30_000L

    /**
     * CONFLATED channel: sending to this channel interrupts the backoff delay and
     * triggers an immediate reconnect attempt.  Used by forceReconnect() and
     * the network-available callback.
     */
    private val reconnectSignal = Channel<Unit>(Channel.CONFLATED)

    /** Invoked on the caller's scope for every inbound platform command after authentication. */
    var onCommand: ((frame: DecodedFrame) -> Unit)? = null

    /** Invoked once each time authentication succeeds (including after reconnects). */
    var onAuthenticated: (() -> Unit)? = null

    // --- Jt808Client interface ---

    override fun connect() { /* managed by start() */ }
    override fun disconnect() { socket?.close() }
    override fun isConnected(): Boolean = socket?.let { !it.isClosed && it.isConnected } == true

    fun isAuthenticated() = authenticated

    /**
     * Closes the current socket (interrupts the blocking read) and signals the reconnect
     * channel so a pending backoff delay exits immediately.
     * Called by the NetworkMonitor when connectivity is restored.
     */
    fun forceReconnect() {
        socket?.close()
        reconnectSignal.trySend(Unit)
    }

    // --- Lifecycle ---

    fun start() {
        scope.launch(Dispatchers.IO) { connectLoop() }
        scope.launch { heartbeatLoop() }
    }

    private suspend fun connectLoop() {
        var backoffMs = 1_000L
        while (true) {
            if (config.serverHost.isBlank()) {
                // Not configured yet — wait silently for forceReconnect() which fires after
                // SettingsActivity saves a host and restarts the service.
                Log.d(TAG, "JT808 host not configured — waiting")
                reconnectSignal.receive()   // blocks until forceReconnect() or network change
                continue
            }
            try {
                connectAndRun()
                backoffMs = 1_000L
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "JT808 disconnected: ${e.message}")
            }
            authenticated = false
            socket = null
            output = null
            // Sleep for backoff OR exit early if forceReconnect() fires — whichever is first.
            withTimeoutOrNull(backoffMs) { reconnectSignal.receive() }
            backoffMs = minOf(backoffMs * 2, 60_000L)
        }
    }

    /**
     * Blocking: runs on Dispatchers.IO until the socket closes or times out.
     *
     * Socket hardening — Phase 8:
     *   SO_KEEPALIVE  true    — OS sends keepalive probes on idle TCP connections
     *   TCP_NODELAY   true    — sends small control frames immediately (no Nagle buffering)
     *   SO_TIMEOUT    90 000  — unblocks the read loop after 3 missed heartbeat intervals,
     *                           which triggers the connectLoop backoff and reconnect
     *   connect timeout 15 s  — fails fast if server is unreachable
     */
    private fun connectAndRun() {
        Log.i(TAG, "Connecting to ${config.serverHost}:${config.serverPort}")
        val sock = Socket().apply {
            keepAlive  = true
            tcpNoDelay = true
            soTimeout  = 90_000   // 3 × default 30-s heartbeat interval
            connect(InetSocketAddress(config.serverHost, config.serverPort), 15_000)
        }
        socket = sock
        output = sock.getOutputStream()
        Log.i(TAG, "TCP connected")

        // Step 1: send registration — JT808-2013 §7.2.1
        send(TERMINAL_REGISTER, Jt808Messages.registration(config))

        val reader = Jt808Codec.FrameReader()
        val buf = ByteArray(4096)
        val input = sock.getInputStream()
        while (!sock.isClosed) {
            val n = input.read(buf)
            if (n < 0) break
            for (raw in reader.feed(buf.copyOf(n))) {
                val frame = Jt808Codec.decodeFrame(raw) ?: continue
                handleInbound(frame)
            }
        }
    }

    private fun handleInbound(frame: DecodedFrame) {
        when (frame.msgId) {
            REGISTRATION_RESPONSE -> handleRegistrationResponse(frame)
            PLATFORM_GENERAL_RESPONSE -> handlePlatformAck(frame)
            else -> {
                // All platform commands require a terminal general response — JT808-2013 §6.1.1
                send(TERMINAL_GENERAL_RESPONSE, Jt808Messages.generalResponse(frame.seqNum, frame.msgId))
                if (authenticated) onCommand?.invoke(frame)
            }
        }
    }

    /**
     * 0x8100 Registration response — JT808-2013 §8.6 Table 8.
     * [0-1] responseSeq WORD, [2] result BYTE, [3+] authCode STRING (only when result=0).
     */
    private fun handleRegistrationResponse(frame: DecodedFrame) {
        if (frame.body.size < 3) return
        val result = frame.body[2].toInt() and 0xFF
        if (result == 0 && frame.body.size > 3) {
            authToken = String(frame.body, 3, frame.body.size - 3, Charsets.UTF_8)
            Log.i(TAG, "Registration OK, auth token received")
        } else {
            Log.w(TAG, "Registration result=$result, proceeding with auth anyway")
        }
        // Step 2: authenticate immediately — JT808-2013 §7.2.2
        send(TERMINAL_AUTH, Jt808Messages.authentication(authToken.ifBlank { config.terminalId }))
    }

    /**
     * 0x8001 Platform general response — JT808-2013 §8.2 Table 5.
     * [0-1] responseSeq WORD, [2-3] responseId WORD, [4] result BYTE.
     */
    private fun handlePlatformAck(frame: DecodedFrame) {
        if (frame.body.size < 5) return
        val responseId = ((frame.body[2].toInt() and 0xFF) shl 8) or (frame.body[3].toInt() and 0xFF)
        val result = frame.body[4].toInt() and 0xFF
        if (responseId == TERMINAL_AUTH && result == 0) {
            authenticated = true
            Log.i(TAG, "Authentication succeeded — terminal ready")
            onAuthenticated?.invoke()
        }
    }

    // Heartbeat — JT808-2013 §5.2. Interval configurable via 0x8103 param 0x0001.
    private suspend fun heartbeatLoop() {
        while (true) {
            delay(heartbeatIntervalMs)
            if (authenticated) send(HEARTBEAT, Jt808Messages.heartbeat())
        }
    }

    // --- Public send API (called by LocationReporter and TerminalService) ---

    fun sendLocationReport(body: ByteArray) {
        if (!authenticated) return
        send(LOCATION_REPORT, body)
    }

    /** Send any authenticated terminal→platform message (e.g. 0x1003, 0x1205). */
    fun sendCommand(msgId: Int, body: ByteArray) {
        if (!authenticated) return
        send(msgId, body)
    }

    /**
     * Sends 0x0800 MultimediaEventUpload followed by 0x0801 MultimediaDataUpload.
     *
     * The 0x0801 body (36-byte header + location block + JPEG payload) is typically
     * larger than the 1023-byte JT808 packet limit and is automatically fragmented
     * into sub-packets via [Jt808Codec.encodeFrames] (JT808-2013 §4.4.3 bit 13).
     * Both messages use independent sequence numbers; the server matches them by mediaId.
     */
    fun sendMultimediaUpload(eventBody: ByteArray, dataBody: ByteArray) {
        if (!authenticated) return
        sendFrames(MsgId.MULTIMEDIA_EVENT_UPLOAD, eventBody)
        sendFrames(MsgId.MULTIMEDIA_DATA_UPLOAD, dataBody)
    }

    @Synchronized
    private fun sendFrames(msgId: Int, body: ByteArray) {
        val out = output ?: return
        try {
            val seq    = seqGen.incrementAndGet() and 0xFFFF
            val frames = Jt808Codec.encodeFrames(msgId, config.phoneNumber, seq, body)
            for (frame in frames) out.write(frame)
            out.flush()
            Log.v(TAG, "TX 0x${msgId.toString(16).uppercase()} seq=$seq " +
                "body=${body.size}B (${frames.size} packet(s))")
        } catch (e: Exception) {
            Log.w(TAG, "TX failed (0x${msgId.toString(16)}): ${e.message}")
        }
    }

    // --- Frame transmission ------------------------------------------------

    @Synchronized
    private fun send(msgId: Int, body: ByteArray) {
        val out = output ?: return
        try {
            val seq = seqGen.incrementAndGet() and 0xFFFF
            val frame = Jt808Codec.encodeFrame(msgId, config.phoneNumber, seq, body)
            out.write(frame)
            out.flush()
            Log.v(TAG, "TX 0x${msgId.toString(16).uppercase()} seq=$seq body=${body.size}B")
        } catch (e: Exception) {
            Log.w(TAG, "TX failed (0x${msgId.toString(16)}): ${e.message}")
        }
    }

    companion object {
        private const val TAG = "Jt808TcpClient"
    }
}
