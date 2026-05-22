package com.example.jt808terminal.jt1078

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.net.Socket

/**
 * TCP connection from the terminal to the RTVS server.
 *
 * Flow (spec §2.3, §4.1 data flow):
 *   1. Server sends 0x9101 → terminal extracts RTVS IP + TCP port from body
 *   2. Terminal opens a NEW TCP connection to RTVS on that IP:port
 *   3. Terminal starts sending JT1078 RTP packets over this connection
 *   4. For intercom (Phase 3): RTVS also pushes downlink audio over the same connection
 *   5. Server sends 0x9102 command=4 → terminal closes this connection
 *
 * This class is write-only for Phase 2. The downlink read path (Phase 3) is
 * added in RtvsConnection by implementing onDownlinkPacket.
 */
class RtvsConnection(
    private val scope: CoroutineScope,
    val channel: Int,
    val dataType: Int,  // 0=AV, 1=video, 2=intercom — from 0x9101 body
) {
    @Volatile private var socket: Socket? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var connected = false

    private var connectJob: Job? = null

    /** Called when a JT1078 downlink packet arrives (Phase 3 intercom). */
    var onDownlinkPacket: ((ByteArray) -> Unit)? = null

    fun connect(host: String, port: Int) {
        connectJob = scope.launch(Dispatchers.IO) {
            try {
                Log.i(TAG, "Connecting to RTVS $host:$port ch=$channel")
                val sock = Socket(host, port)
                socket = sock
                output = sock.getOutputStream()
                connected = true
                Log.i(TAG, "RTVS connected")

                // Downlink read loop — JT/T 1078-2016 §5.5 / §6.1.
                // Blocks until the socket closes or disconnect() is called.
                val parser = Jt1078StreamParser(sock.getInputStream())
                while (!sock.isClosed) {
                    val frame = parser.readFrame() ?: break
                    onDownlinkPacket?.invoke(frame)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "RTVS error ch=$channel: ${e.message}")
            } finally {
                connected = false
                Log.i(TAG, "RTVS disconnected ch=$channel")
            }
        }
    }

    fun isConnected() = connected

    @Synchronized
    fun send(packet: ByteArray) {
        val out = output ?: return
        try {
            out.write(packet)
            // No flush on every packet — OS buffers are efficient enough for streaming
        } catch (e: Exception) {
            Log.w(TAG, "RTVS send failed: ${e.message}")
            connected = false
        }
    }

    fun flush() {
        try { output?.flush() } catch (_: Exception) {}
    }

    fun disconnect() {
        connected = false
        connectJob?.cancel()
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        output = null
    }

    companion object {
        private const val TAG = "RtvsConnection"
    }
}
