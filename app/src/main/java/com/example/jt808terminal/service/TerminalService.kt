package com.example.jt808terminal.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.camera.core.ImageAnalysis
import com.example.jt808terminal.core.TerminalConfig
import com.example.jt808terminal.dms.DmsAlarmState
import com.example.jt808terminal.dms.DmsEngine
import com.example.jt808terminal.jt1078.IntercomManager
import com.example.jt808terminal.jt1078.Jt1078Command
import com.example.jt808terminal.jt1078.RtvsConnection
import com.example.jt808terminal.jt1078.VideoEncoder
import com.example.jt808terminal.protocol.Jt808TcpClientImpl
import com.example.jt808terminal.protocol.LocationReporter
import com.example.jt808terminal.protocol.MsgId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Foreground service hosting all vehicle terminal subsystems.
 * Extends LifecycleService so it acts as a LifecycleOwner for CameraX binding.
 *
 * Phase 1: JT808 TCP signaling (registration, auth, heartbeat, 0x0200 location)
 * Phase 2: JT1078 video streaming via RtvsConnection + VideoEncoder
 * Phase 3: Two-way intercom via IntercomManager (G.711A, dataType=2)
 * Phase 4: DMS — fatigue/yawning via DmsEngine (ML Kit, PERCLOS 60-s window)
 * Phase 5: ADAS — add AdasEngine here
 * Phase 6: BSD  — add BsdDetector here
 */
class TerminalService : LifecycleService() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var config: TerminalConfig
    private lateinit var jt808Client: Jt808TcpClientImpl
    private lateinit var locationReporter: LocationReporter

    // Phase 4 — DMS (created once; ImageAnalysis use case reused across streaming sessions)
    private lateinit var dmsAlarmState: DmsAlarmState
    private lateinit var dmsEngine: DmsEngine
    private var dmsImageAnalysis: ImageAnalysis? = null

    // Phase 2 — active streaming state (null when not streaming)
    private var rtvsConnection: RtvsConnection? = null
    private var videoEncoder: VideoEncoder? = null
    // Phase 3 — active intercom (null when not in intercom session)
    private var intercomManager: IntercomManager? = null

    override fun onCreate() {
        super.onCreate()
        config = buildConfig()

        dmsAlarmState = DmsAlarmState()
        dmsEngine = DmsEngine(dmsAlarmState)
        dmsImageAnalysis = dmsEngine.getImageAnalysis()

        jt808Client = Jt808TcpClientImpl(config, scope)
        jt808Client.onCommand = { frame ->
            handlePlatformCommand(frame.msgId, frame.seqNum, frame.body)
        }

        locationReporter = LocationReporter(this, jt808Client, scope, dmsAlarmState = dmsAlarmState)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        ensureChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        jt808Client.start()
        locationReporter.start()

        Log.i(TAG, "TerminalService started")
        return START_STICKY
    }

    override fun onDestroy() {
        stopStreamingOnMain()
        scope.cancel()
        jt808Client.disconnect()
        dmsEngine.stop()
        super.onDestroy()
    }

    // ---- Platform command dispatch ----------------------------------------

    private fun handlePlatformCommand(msgId: Int, seqNum: Int, body: ByteArray) {
        when (msgId) {
            MsgId.REALTIME_AV_REQUEST -> handle9101(body)
            MsgId.AV_CONTROL          -> handle9102(body)
            MsgId.AV_STATUS_NOTIFY    -> {} // platform → terminal direction; terminal just ACKs
            MsgId.PARAMETER_SETTING   -> Log.i(TAG, "0x8103 params (${body.size}B) — Phase 8")
            MsgId.AV_ATTRIBUTES_QUERY -> Log.i(TAG, "0x9003 AV attr query — Phase 2 TODO")
            else -> Log.d(TAG, "Unhandled 0x${msgId.toString(16).uppercase()}")
        }
    }

    /**
     * 0x9101 Real-time A/V request — JT/T 1078-2016 §5.5.1.
     * Body (decoded by Jt1078Command helper):
     *   [0]     IP length BYTE
     *   [1..n]  Server IP STRING
     *   [n+1-2] TCP port WORD
     *   [n+3-4] UDP port WORD
     *   [n+5]   Channel BYTE
     *   [n+6]   Data type BYTE  (0=AV, 1=video, 2=intercom, 3=listen, 4=broadcast)
     *   [n+7]   Stream type BYTE (0=main, 1=sub)
     */
    private fun handle9101(body: ByteArray) {
        val cmd = Jt1078Command.parse9101(body) ?: run {
            Log.w(TAG, "0x9101 parse failed (${body.size}B)")
            return
        }
        Log.i(TAG, "0x9101 → ${cmd.host}:${cmd.tcpPort} ch=${cmd.channel} type=${cmd.dataType}")
        mainHandler.post { startStreaming(cmd) }
    }

    /**
     * 0x9102 A/V transmission control — JT/T 1078-2016 §5.5.2.
     * Body: channel(1) + command(1) + param(1) + streamType(1)
     * Commands: 1=pause, 2=resume, 3=switch, 4=close intercom
     */
    private fun handle9102(body: ByteArray) {
        if (body.size < 4) return
        val command = body[1].toInt() and 0xFF
        Log.i(TAG, "0x9102 command=$command")
        when (command) {
            4 -> mainHandler.post { stopStreamingOnMain() }  // close intercom / stop stream
            1 -> Log.i(TAG, "0x9102 pause — Phase 8")
            2 -> Log.i(TAG, "0x9102 resume — Phase 8")
            3 -> Log.i(TAG, "0x9102 switch stream — Phase 8")
        }
    }

    // ---- Streaming lifecycle (must run on main thread for CameraX) ---------

    private fun startStreaming(cmd: Jt1078Command.Request9101) {
        stopStreamingOnMain()

        val conn = RtvsConnection(scope, cmd.channel, cmd.dataType)
        conn.connect(cmd.host, cmd.tcpPort)
        rtvsConnection = conn

        when (cmd.dataType) {
            // dataType=0 (AV) or dataType=1 (video only) — JT/T 1078-2016 §5.5.1
            0, 1 -> {
                val enc = VideoEncoder(
                    context = this,
                    phoneNumber = config.phoneNumber,
                    channel = cmd.channel,
                    rtvsConnection = conn,
                )
                // Bind DMS ImageAnalysis alongside Preview so both share one camera session.
                val dmsAnalysis = dmsImageAnalysis
                if (dmsAnalysis != null) enc.start(this, dmsAnalysis) else enc.start(this)
                videoEncoder = enc
            }
            // dataType=2 — two-way intercom, audio only — JT/T 1078-2016 §5.5.1
            2 -> {
                val intercom = IntercomManager(config.phoneNumber, cmd.channel, conn)
                intercom.start()
                intercomManager = intercom
            }
            // dataType=3 (listen) and dataType=4 (broadcast) — server-initiated, no uplink
        }
    }

    private fun stopStreamingOnMain() {
        intercomManager?.stop()
        intercomManager = null
        videoEncoder?.stop()
        videoEncoder = null
        rtvsConnection?.disconnect()
        rtvsConnection = null
    }

    // ---- Config / notification --------------------------------------------

    private fun buildConfig() = TerminalConfig(
        phoneNumber = "000000000001",   // TODO: replace with SIM phone number (12 digits)
        terminalId  = "GOATAI1",        // TODO: replace with 7-char device serial
        serverHost  = "192.168.1.100",  // TODO: set your jt808-server IP
        serverPort  = 6808,
        rtvsHost    = "192.168.1.100",  // TODO: set your jt808-rtvs IP
        rtvsPort    = 6600,
    )

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("JT808 Terminal")
            .setContentText("Vehicle monitoring active")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Terminal", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        private const val TAG = "TerminalService"
        private const val CHANNEL_ID = "terminal"
        private const val NOTIFICATION_ID = 1
    }
}
