package com.example.jt808terminal.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.example.jt808terminal.core.AppSettings
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service hosting all vehicle terminal subsystems.
 * Extends LifecycleService so it acts as a LifecycleOwner for CameraX binding.
 *
 * Camera is ALWAYS ON once the service starts — DMS/ADAS/BSD run regardless of server state.
 * When 0x9101 arrives the Preview use case is added alongside the always-on ImageAnalysis.
 * When 0x9102 stops streaming, the Preview is removed but DMS camera session stays alive.
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

    // DMS — always-on from service start
    private lateinit var dmsAlarmState: DmsAlarmState
    private lateinit var dmsEngine: DmsEngine
    @Volatile private var dmsImageAnalysis: ImageAnalysis? = null

    // Active streaming session (null when not streaming)
    private var rtvsConnection: RtvsConnection? = null
    private var videoEncoder: VideoEncoder? = null
    private var intercomManager: IntercomManager? = null

    override fun onCreate() {
        super.onCreate()
        config = buildConfig()

        dmsAlarmState = DmsAlarmState()
        dmsEngine = DmsEngine(dmsAlarmState)

        // ML Kit init on IO thread, then start always-on camera on Main.
        scope.launch(Dispatchers.IO) {
            dmsImageAnalysis = dmsEngine.getImageAnalysis()
            withContext(Dispatchers.Main) {
                Log.i(TAG, "DMS ready — starting always-on camera")
                bindCamera()   // DMS-only session, runs before any 0x9101 arrives
            }
        }

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
        stopStreaming()
        scope.cancel()
        jt808Client.disconnect()
        dmsEngine.stop()
        super.onDestroy()
    }

    // ---- Camera binding (all CameraX lifecycle management lives here) --------

    /**
     * Binds DMS ImageAnalysis only — the always-on camera session.
     * Called at startup and after streaming stops.
     */
    private fun bindCamera() {
        val analysis = dmsImageAnalysis ?: return
        withCameraProvider { provider ->
            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                analysis,
            )
            Log.i(TAG, "Camera bound — DMS always-on")
        }
    }

    /**
     * Binds Preview (for encoder) + DMS ImageAnalysis together.
     * The encoder's input surface is already created at this point (setupCodec runs in init).
     */
    private fun bindCameraWithStreaming(enc: VideoEncoder) {
        val analysis = dmsImageAnalysis
        withCameraProvider { provider ->
            val preview = Preview.Builder()
                .setTargetResolution(Size(enc.videoWidth, enc.videoHeight))
                .build()
            preview.setSurfaceProvider { request ->
                request.provideSurface(
                    enc.getInputSurface(),
                    ContextCompat.getMainExecutor(this),
                ) {}
            }
            provider.unbindAll()
            if (analysis != null) {
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
            } else {
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview)
            }
            Log.i(TAG, "Camera bound — streaming + DMS")
        }
    }

    private fun withCameraProvider(block: (ProcessCameraProvider) -> Unit) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try { block(future.get()) }
            catch (e: Exception) { Log.e(TAG, "CameraProvider error: ${e.message}") }
        }, ContextCompat.getMainExecutor(this))
    }

    // ---- Platform command dispatch -------------------------------------------

    private fun handlePlatformCommand(msgId: Int, seqNum: Int, body: ByteArray) {
        when (msgId) {
            MsgId.REALTIME_AV_REQUEST -> handle9101(body)
            MsgId.AV_CONTROL          -> handle9102(body)
            MsgId.AV_STATUS_NOTIFY    -> {}
            MsgId.PARAMETER_SETTING   -> Log.i(TAG, "0x8103 params (${body.size}B) — Phase 8")
            MsgId.AV_ATTRIBUTES_QUERY -> Log.i(TAG, "0x9003 AV attr query — Phase 2 TODO")
            else -> Log.d(TAG, "Unhandled 0x${msgId.toString(16).uppercase()}")
        }
    }

    /**
     * 0x9101 Real-time A/V request — JT/T 1078-2016 §5.5.1.
     * Body: IP length(1) + IP(n) + tcpPort(2) + udpPort(2) + channel(1) + dataType(1) + streamType(1)
     * dataType: 0=AV 1=video 2=intercom 3=listen 4=broadcast
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
     * command 4 = close / stop stream
     */
    private fun handle9102(body: ByteArray) {
        if (body.size < 2) return
        val command = body[1].toInt() and 0xFF
        Log.i(TAG, "0x9102 command=$command")
        when (command) {
            4 -> mainHandler.post { stopStreaming() }
            1 -> Log.i(TAG, "0x9102 pause — Phase 8")
            2 -> Log.i(TAG, "0x9102 resume — Phase 8")
            3 -> Log.i(TAG, "0x9102 switch stream — Phase 8")
        }
    }

    // ---- Streaming lifecycle (main thread) -----------------------------------

    private fun startStreaming(cmd: Jt1078Command.Request9101) {
        stopStreaming()

        val conn = RtvsConnection(scope, cmd.channel, cmd.dataType)
        conn.connect(cmd.host, cmd.tcpPort)
        rtvsConnection = conn

        when (cmd.dataType) {
            0, 1 -> {
                val enc = VideoEncoder(
                    phoneNumber  = config.phoneNumber,
                    channel      = cmd.channel,
                    rtvsConnection = conn,
                )
                enc.start()
                videoEncoder = enc
                bindCameraWithStreaming(enc)   // adds Preview alongside always-on DMS
            }
            2 -> {
                val intercom = IntercomManager(config.phoneNumber, cmd.channel, conn)
                intercom.start()
                intercomManager = intercom
                // Audio-only intercom: camera stays in DMS-only mode (no Preview change)
            }
            // dataType 3/4 — server-initiated listen/broadcast; no uplink from terminal
        }
    }

    private fun stopStreaming() {
        intercomManager?.stop(); intercomManager = null
        videoEncoder?.stop();    videoEncoder = null
        rtvsConnection?.disconnect(); rtvsConnection = null
        // Revert camera to DMS-only session after streaming ends
        bindCamera()
    }

    // ---- Config / notification ----------------------------------------------

    private fun buildConfig(): TerminalConfig {
        val s = AppSettings(this)
        return TerminalConfig(
            phoneNumber = s.phoneNumber,
            terminalId  = s.terminalId,
            serverHost  = s.jt808Host,
            serverPort  = s.jt808Port,
            rtvsHost    = s.rtvsHost,
            rtvsPort    = s.rtvsPort,
        )
    }

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
