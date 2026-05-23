package com.example.jt808terminal.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.example.jt808terminal.adas.AdasAlarmState
import com.example.jt808terminal.adas.AdasEngine
import com.example.jt808terminal.bsd.BsdAlarmState
import com.example.jt808terminal.bsd.BsdEngine
import com.example.jt808terminal.core.AppSettings
import com.example.jt808terminal.core.TerminalConfig
import com.example.jt808terminal.dms.DmsAlarmState
import com.example.jt808terminal.dms.DmsEngine
import com.example.jt808terminal.jt1078.IntercomManager
import com.example.jt808terminal.jt1078.Jt1078Command
import com.example.jt808terminal.jt1078.RtvsConnection
import com.example.jt808terminal.jt1078.VideoEncoder
import com.example.jt808terminal.media.FtpUploader
import com.example.jt808terminal.media.PlaybackSession
import com.example.jt808terminal.db.TerminalDatabase
import com.example.jt808terminal.media.DvrManager
import com.example.jt808terminal.media.RecordingIndex
import com.example.jt808terminal.media.StorageManager
import com.example.jt808terminal.protocol.Jt808Messages
import com.example.jt808terminal.protocol.Jt808TcpClientImpl
import com.example.jt808terminal.protocol.LocationReporter
import com.example.jt808terminal.protocol.MsgId
import java.io.File
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
 * Phase 4: DMS — fatigue/yawning via DmsEngine (ML Kit Face Detection, PERCLOS 60-s window)
 * Phase 5: ADAS — FCW/LDW/pedestrian via AdasEngine (ML Kit Object Detection, back camera)
 * Phase 6: BSD  — blind-spot detection via BsdEngine (co-analysis on same back-camera frames)
 */
class TerminalService : LifecycleService() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var config: TerminalConfig
    private lateinit var jt808Client: Jt808TcpClientImpl
    private lateinit var locationReporter: LocationReporter

    // DMS — always-on from service start (front camera)
    private lateinit var dmsAlarmState: DmsAlarmState
    private lateinit var dmsEngine: DmsEngine
    @Volatile private var dmsImageAnalysis: ImageAnalysis? = null

    // ADAS + BSD — always-on from service start (back camera)
    private lateinit var adasAlarmState: AdasAlarmState
    private lateinit var bsdAlarmState: BsdAlarmState
    private lateinit var adasEngine: AdasEngine
    @Volatile private var adasImageAnalysis: ImageAnalysis? = null

    // Network connectivity callback — triggers JT808 reconnect when network returns (Phase 8).
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.i(TAG, "Network available — forcing JT808 reconnect")
            jt808Client.forceReconnect()
        }
        override fun onLost(network: Network) {
            Log.w(TAG, "Network lost")
        }
    }

    // Phase 7 — recording, playback, file upload
    private lateinit var db: TerminalDatabase
    private lateinit var recordingIndex: RecordingIndex
    private lateinit var dvrManager: DvrManager
    private var activePlayback: PlaybackSession? = null
    private var activeFtpUploader: FtpUploader? = null

    // Active streaming session (null when not streaming)
    private var rtvsConnection: RtvsConnection? = null
    private var videoEncoder: VideoEncoder? = null
    private var intercomManager: IntercomManager? = null

    override fun onCreate() {
        super.onCreate()
        config = buildConfig()

        // Prevent CPU sleep during active driving session — spec §10.3
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JT808Terminal:main")
            .also { it.acquire() }

        dmsAlarmState    = DmsAlarmState()
        dmsEngine        = DmsEngine(dmsAlarmState)
        adasAlarmState   = AdasAlarmState()
        bsdAlarmState    = BsdAlarmState()
        val bsdEngine    = BsdEngine(bsdAlarmState)
        adasEngine       = AdasEngine(adasAlarmState, bsdEngine)
        db               = TerminalDatabase.getInstance(this)
        recordingIndex   = RecordingIndex(db.dvrSegmentDao())
        dvrManager       = DvrManager(this, channel = 1, db.dvrSegmentDao(), scope)

        // ML Kit init on IO thread (both DMS and ADAS detectors), then bind both cameras on Main.
        scope.launch(Dispatchers.IO) {
            dmsImageAnalysis  = dmsEngine.getImageAnalysis()
            adasImageAnalysis = adasEngine.getImageAnalysis()
            withContext(Dispatchers.Main) {
                Log.i(TAG, "DMS + ADAS ready — starting always-on cameras")
                rebindAllCameras()
                dvrManager.start()
            }
        }

        jt808Client = Jt808TcpClientImpl(config, scope)
        jt808Client.onCommand = { frame ->
            handlePlatformCommand(frame.msgId, frame.seqNum, frame.body)
        }

        // Restore server-configured parameters persisted by previous 0x8103 messages.
        val settings = AppSettings(this)
        jt808Client.heartbeatIntervalMs        = settings.heartbeatIntervalSec.toLong() * 1000L
        adasAlarmState.overSpeedAlarmKph       = settings.overSpeedAlarmKph.toFloat()
        adasAlarmState.overSpeedWarningKph     = (settings.overSpeedAlarmKph - settings.overSpeedWarningGapTenthKph / 10f).coerceAtLeast(1f)
        val locationIntervalMs                 = settings.locationReportIntervalSec.toLong() * 1000L

        locationReporter = LocationReporter(
            this, jt808Client, scope,
            intervalMs     = locationIntervalMs,
            dmsAlarmState  = dmsAlarmState,
            adasAlarmState = adasAlarmState,
            bsdAlarmState  = bsdAlarmState,
            gpsPointDao    = db.gpsPointDao(),
        )

        // Network connectivity monitoring — reconnect promptly on network restore (Phase 8).
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(req, networkCallback)

        // Evict old recordings on startup to reclaim storage (Phase 8).
        scope.launch(Dispatchers.IO) {
            StorageManager.evict(db.dvrSegmentDao(), AppSettings(this@TerminalService).maxStorageMb)
        }

        // DMS danger alarm → immediate 0x0200 + tag DVR segment as alarm clip
        dmsEngine.onAlarmLevelChange = { level ->
            if (level >= 2) {
                locationReporter.reportNow()
                dvrManager.markAlarm(1L shl 14)   // Table 24 bit 14: Fatigue driving warning
            }
        }

        // ADAS FCW → immediate 0x0200 + tag DVR segment as alarm clip — Table 24 bit 29
        adasEngine.onCollisionWarning = {
            locationReporter.reportNow()
            dvrManager.markAlarm(1L shl 29)
        }
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
        (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
            .unregisterNetworkCallback(networkCallback)
        stopStreaming()
        activePlayback?.stop(); activePlayback = null
        activeFtpUploader?.isCancelled = true; activeFtpUploader = null
        dvrManager.stop()
        scope.cancel()
        jt808Client.disconnect()
        dmsEngine.stop()
        adasEngine.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    // ---- Camera binding (all CameraX lifecycle management lives here) --------

    /**
     * Single entry point for all camera rebinds.
     *
     * Front camera (DEFAULT_FRONT_CAMERA): DMS ImageAnalysis always-on; optional streaming Preview.
     * Back camera  (DEFAULT_BACK_CAMERA):  ADAS+BSD ImageAnalysis always-on.
     *
     * Called on the main thread. [streamingPreview] is non-null only during active video sessions.
     */
    private fun rebindAllCameras(streamingPreview: Preview? = null) {
        withCameraProvider { provider ->
            provider.unbindAll()

            // Front camera — DMS face detection + optional video preview for streaming
            val dmsAnalysis = dmsImageAnalysis
            if (dmsAnalysis != null) {
                if (streamingPreview != null) {
                    provider.bindToLifecycle(
                        this, CameraSelector.DEFAULT_FRONT_CAMERA,
                        streamingPreview, dmsAnalysis,
                    )
                    Log.i(TAG, "Front camera bound — streaming + DMS")
                } else {
                    provider.bindToLifecycle(
                        this, CameraSelector.DEFAULT_FRONT_CAMERA,
                        dmsAnalysis,
                    )
                    Log.i(TAG, "Front camera bound — DMS only")
                }
            }

            // Back camera — ADAS+BSD ImageAnalysis + VideoCapture for local recording
            val adasAnalysis = adasImageAnalysis
            val videoCapture = dvrManager.videoCapture
            if (adasAnalysis != null) {
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA,
                    adasAnalysis, videoCapture,
                )
                Log.i(TAG, "Back camera bound — ADAS+BSD+Recording")
            }
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
            MsgId.AV_STATUS_NOTIFY    -> {}   // packet-loss feedback — Phase 8
            MsgId.PARAMETER_SETTING   -> handle8103(body)
            // 0x9003 — Query A/V attributes: reply with 0x1003 (codec, sample rate, channels)
            MsgId.AV_ATTRIBUTES_QUERY -> {
                jt808Client.sendCommand(MsgId.UPLOAD_AV_ATTRIBUTES, Jt808Messages.avAttributes())
                Log.i(TAG, "0x9003 → 0x1003 AV attributes sent (G.711A + H.264)")
            }
            // 0x9205 — Resource list query → 0x1205 response — JT/T 1078-2016 §5.6.1/§5.6.2
            MsgId.QUERY_RESOURCE_LIST -> handle9205(body, seqNum)
            // 0x9201 — Playback request — JT/T 1078-2016 §5.6.3
            MsgId.PLAYBACK_REQUEST  -> mainHandler.post { handle9201(body, seqNum) }
            // 0x9202 — Playback control — JT/T 1078-2016 §5.6.4
            MsgId.PLAYBACK_CONTROL  -> handle9202(body)
            // 0x9206 — File upload command — JT/T 1078-2016 §5.6.5
            MsgId.FILE_UPLOAD_CMD   -> handle9206(body, seqNum)
            // 0x9207 — File upload control — JT/T 1078-2016 §5.6.7
            MsgId.FILE_UPLOAD_CONTROL -> handle9207(body)
            MsgId.PTZ_CONTROL, MsgId.FOCUS_CONTROL, MsgId.APERTURE_CONTROL,
            MsgId.WIPER_CONTROL, MsgId.INFRARED_CONTROL, MsgId.ZOOM_CONTROL ->
                Log.d(TAG, "PTZ/camera control 0x${msgId.toString(16)} — no PTZ hardware")
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

    // ---- Phase 7: playback + file management handlers -----------------------

    /**
     * 0x9205 Resource list query — JT/T 1078-2016 §5.6.1 Table 21.
     * Body: [0]=channel, [1-6]=startBCD, [7-12]=endBCD, [13-20]=alarmFlags(8B),
     *       [21]=resourceType, [22]=streamType, [23]=memoryType
     */
    private fun handle9205(body: ByteArray, seqNum: Int) {
        if (body.size < 24) {
            jt808Client.sendCommand(MsgId.UPLOAD_RESOURCE_LIST, Jt808Messages.resourceListEmpty(seqNum))
            return
        }
        val channel      = body[0].toInt() and 0xFF
        val startMs      = Jt1078Command.bcdToEpochMs(body, 1)
        val endMs        = Jt1078Command.bcdToEpochMs(body, 7)
        val alarmFlags   = run { var v = 0L; for (i in 0..7) v = (v shl 8) or (body[13 + i].toLong() and 0xFF); v }
        val resourceType = body[21].toInt() and 0xFF

        val matches = recordingIndex.query(channel, startMs, endMs, alarmFlags, resourceType)
        val body1205 = if (matches.isEmpty()) Jt808Messages.resourceListEmpty(seqNum)
                       else Jt808Messages.resourceList(seqNum, matches)
        jt808Client.sendCommand(MsgId.UPLOAD_RESOURCE_LIST, body1205)
        Log.i(TAG, "0x9205 → 0x1205 ${matches.size} recording(s) (seq=$seqNum)")
    }

    /**
     * 0x9201 Remote playback request — JT/T 1078-2016 §5.6.3 Table 24.
     * Terminal first sends 0x1205 with matching entries, then streams the recording.
     */
    private fun handle9201(body: ByteArray, seqNum: Int) {
        val cmd = Jt1078Command.parse9201(body) ?: run {
            Log.w(TAG, "0x9201 parse failed (${body.size}B)")
            return
        }
        Log.i(TAG, "0x9201 → ${cmd.host}:${cmd.tcpPort} ch=${cmd.channel} method=${cmd.playbackMethod}")

        val matches = recordingIndex.query(
            channel      = cmd.channel,
            startMs      = cmd.startMs,
            endMs        = cmd.endMs,
            alarmFlags   = 0L,
            resourceType = cmd.avType,
        )
        // Reply with matching resource list first — spec §5.6.3
        val body1205 = if (matches.isEmpty()) Jt808Messages.resourceListEmpty(seqNum)
                       else Jt808Messages.resourceList(seqNum, matches)
        jt808Client.sendCommand(MsgId.UPLOAD_RESOURCE_LIST, body1205)

        if (matches.isEmpty()) { Log.i(TAG, "0x9201: no matching recordings"); return }

        activePlayback?.stop()
        val entry = matches.first()
        val conn  = RtvsConnection(scope, cmd.channel, dataType = 1 /* video */)
        conn.connect(cmd.host, cmd.tcpPort)
        val session = PlaybackSession(
            phoneNumber     = config.phoneNumber,
            channel         = cmd.channel,
            entry           = entry,
            conn            = conn,
            scope           = scope,
            playbackMethod  = cmd.playbackMethod,
            speedMultiplier = cmd.speedMultiplier,
        )
        activePlayback = session
        session.start()
    }

    /** 0x9202 Playback control — JT/T 1078-2016 §5.6.4 Table 25. */
    private fun handle9202(body: ByteArray) {
        val cmd = Jt1078Command.parse9202(body) ?: return
        val session = activePlayback ?: run {
            Log.w(TAG, "0x9202 control=${cmd.control} but no active playback")
            return
        }
        val ctrlCmd = when (cmd.control) {
            0 -> PlaybackSession.ControlCmd.START
            1 -> PlaybackSession.ControlCmd.PAUSE
            2 -> PlaybackSession.ControlCmd.STOP
            3 -> PlaybackSession.ControlCmd.FAST_FORWARD
            4 -> PlaybackSession.ControlCmd.KEYFRAME_SNAP
            5 -> PlaybackSession.ControlCmd.SEEK
            6 -> PlaybackSession.ControlCmd.KEYFRAME_ONLY
            else -> { Log.w(TAG, "0x9202 unknown control=${cmd.control}"); return }
        }
        session.control.trySend(ctrlCmd to cmd.seekMs)
        if (cmd.control == 2) { activePlayback = null }
        Log.i(TAG, "0x9202 ctrl=${cmd.control} ch=${cmd.channel}")
    }

    /**
     * 0x9206 File upload command — JT/T 1078-2016 §5.6.5 Table 26.
     * Finds matching recordings, uploads to FTP, sends 0x1206 on completion.
     */
    private fun handle9206(body: ByteArray, seqNum: Int) {
        val cmd = Jt1078Command.parse9206(body, seqNum) ?: run {
            Log.w(TAG, "0x9206 parse failed (${body.size}B)")
            return
        }
        Log.i(TAG, "0x9206 FTP ${cmd.ftpHost}:${cmd.ftpPort} path=${cmd.uploadPath}")

        val matches = recordingIndex.query(
            channel      = cmd.channel,
            startMs      = cmd.startMs,
            endMs        = cmd.endMs,
            alarmFlags   = cmd.alarmFlags,
            resourceType = cmd.resourceType,
        )
        if (matches.isEmpty()) {
            jt808Client.sendCommand(MsgId.FILE_UPLOAD_COMPLETE,
                Jt808Messages.fileUploadComplete(seqNum, success = true))
            return
        }

        val uploader = FtpUploader(cmd.ftpHost, cmd.ftpPort, cmd.username, cmd.password, cmd.uploadPath)
        activeFtpUploader = uploader
        scope.launch {
            val files = matches.map { File(it.filePath) }.filter { it.exists() }
            val ok = uploader.upload(files)
            jt808Client.sendCommand(MsgId.FILE_UPLOAD_COMPLETE,
                Jt808Messages.fileUploadComplete(seqNum, ok))
            if (activeFtpUploader === uploader) activeFtpUploader = null
            Log.i(TAG, "0x9206 upload complete success=$ok (${files.size} file(s))")
        }
    }

    /** 0x9207 File upload control — JT/T 1078-2016 §5.6.7 Table 28. */
    private fun handle9207(body: ByteArray) {
        val cmd = Jt1078Command.parse9207(body) ?: return
        val uploader = activeFtpUploader ?: return
        when (cmd.uploadControl) {
            0 -> { uploader.isPaused    = true;  Log.i(TAG, "0x9207 upload suspended") }
            1 -> { uploader.isPaused    = false; Log.i(TAG, "0x9207 upload resumed") }
            2 -> { uploader.isCancelled = true;  activeFtpUploader = null
                   Log.i(TAG, "0x9207 upload cancelled") }
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
                    phoneNumber    = config.phoneNumber,
                    channel        = cmd.channel,
                    rtvsConnection = conn,
                )
                enc.start()
                videoEncoder = enc

                // Build streaming Preview and rebind front + back cameras together.
                val preview = Preview.Builder()
                    .setTargetResolution(Size(enc.videoWidth, enc.videoHeight))
                    .build()
                preview.setSurfaceProvider { request ->
                    request.provideSurface(
                        enc.getInputSurface(),
                        ContextCompat.getMainExecutor(this),
                    ) {}
                }
                rebindAllCameras(streamingPreview = preview)
            }
            2 -> {
                val intercom = IntercomManager(config.phoneNumber, cmd.channel, conn)
                intercom.start()
                intercomManager = intercom
                // Audio-only intercom: camera stays in DMS+ADAS mode (no Preview change)
            }
            // dataType 3/4 — server-initiated listen/broadcast; no uplink from terminal
        }
    }

    private fun stopStreaming() {
        intercomManager?.stop(); intercomManager = null
        videoEncoder?.stop();    videoEncoder = null
        rtvsConnection?.disconnect(); rtvsConnection = null
        // Revert front camera to DMS-only; back camera ADAS+BSD remains unaffected.
        rebindAllCameras()
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

    // ---- Phase 8: 0x8103 parameter setting -----------------------------------

    /**
     * 0x8103 Terminal parameter setting — JT808-2013 §8.9 Table 10/11/12.
     *
     * Body: [0]=count(BYTE) + N×{ paramId(DWORD) + paramLen(BYTE) + value(BYTE[n]) }
     *
     * Parameters handled:
     *   0x0001  DWORD  Heartbeat interval (seconds)      → jt808Client.heartbeatIntervalMs
     *   0x0029  DWORD  Location report interval (seconds) → locationReporter.intervalMs
     *   0x0055  DWORD  Overspeed alarm threshold (km/h)   → adasAlarmState.overSpeedAlarmKph
     *   0x005B  WORD   Alarm-warning gap (1/10 km/h)      → adasAlarmState.overSpeedWarningKph
     */
    private fun handle8103(body: ByteArray) {
        if (body.isEmpty()) return
        val count = body[0].toInt() and 0xFF
        var pos = 1
        val s = AppSettings(this)
        repeat(count) {
            if (pos + 5 > body.size) return@repeat
            val paramId  = u32be(body, pos); pos += 4
            val paramLen = body[pos++].toInt() and 0xFF
            if (pos + paramLen > body.size) return@repeat
            val value = body.copyOfRange(pos, pos + paramLen); pos += paramLen
            when (paramId) {
                0x0001 -> {   // Heartbeat interval — JT808-2013 Table 12
                    val secs = u32be(value, 0).toLong().coerceAtLeast(5)
                    jt808Client.heartbeatIntervalMs = secs * 1_000L
                    // Update SO_TIMEOUT to cover 3 heartbeat intervals (takes effect on next connect)
                    s.heartbeatIntervalSec = secs.toInt()
                    Log.i(TAG, "0x8103: heartbeat interval → ${secs}s")
                }
                0x0029 -> {   // Location report interval — JT808-2013 Table 12
                    val secs = u32be(value, 0).toLong().coerceAtLeast(1)
                    locationReporter.intervalMs = secs * 1_000L
                    s.locationReportIntervalSec = secs.toInt()
                    Log.i(TAG, "0x8103: location interval → ${secs}s")
                }
                0x0055 -> {   // Highest (alarm) speed in km/h — JT808-2013 Table 12
                    val kph = u32be(value, 0).toFloat().coerceAtLeast(1f)
                    adasAlarmState.overSpeedAlarmKph = kph
                    s.overSpeedAlarmKph = kph.toInt()
                    val gapKph = s.overSpeedWarningGapTenthKph / 10f
                    adasAlarmState.overSpeedWarningKph = (kph - gapKph).coerceAtLeast(1f)
                    Log.i(TAG, "0x8103: overspeed alarm → ${kph}km/h warning → ${adasAlarmState.overSpeedWarningKph}km/h")
                }
                0x005B -> {   // Alarm-warning gap in 1/10 km/h — JT808-2013 Table 12
                    if (value.size >= 2) {
                        val gapTenth = ((value[0].toInt() and 0xFF) shl 8) or (value[1].toInt() and 0xFF)
                        val gapKph   = gapTenth / 10f
                        s.overSpeedWarningGapTenthKph = gapTenth
                        adasAlarmState.overSpeedWarningKph = (adasAlarmState.overSpeedAlarmKph - gapKph).coerceAtLeast(1f)
                        Log.i(TAG, "0x8103: overspeed gap → ${gapKph}km/h → warning ${adasAlarmState.overSpeedWarningKph}km/h")
                    }
                }
                else -> Log.d(TAG, "0x8103: unhandled param 0x${paramId.toString(16).uppercase()}")
            }
        }
    }

    // ---- Phase 8: memory pressure --------------------------------------------

    /**
     * Android calls this on the foreground service when the system is running low.
     *
     * RUNNING_CRITICAL: cancel any active FTP upload (non-safety, IO-intensive).
     * Lower levels: no action — DMS/ADAS are safety-critical and must continue running.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            Log.w(TAG, "onTrimMemory level=$level — cancelling FTP upload to reclaim memory")
            activeFtpUploader?.isCancelled = true
            activeFtpUploader = null
        }
    }

    // ---- Helpers -------------------------------------------------------------

    private fun u32be(buf: ByteArray, offset: Int): Int {
        if (offset + 3 >= buf.size) return 0
        return ((buf[offset].toInt() and 0xFF) shl 24) or
               ((buf[offset + 1].toInt() and 0xFF) shl 16) or
               ((buf[offset + 2].toInt() and 0xFF) shl 8) or
               (buf[offset + 3].toInt() and 0xFF)
    }

    companion object {
        private const val TAG = "TerminalService"
        private const val CHANNEL_ID = "terminal"
        private const val NOTIFICATION_ID = 1
    }
}
