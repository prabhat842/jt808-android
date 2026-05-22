package com.example.jt808terminal.media

import android.content.Context
import android.util.Log
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Timer
import java.util.TimerTask

/**
 * Manages continuous back-camera recording in 5-minute segments — Phase 7.
 *
 * Each segment is an MP4 file stored in [Context.filesDir]/recordings/.
 * On segment completion a [RecordingEntry] is written to [RecordingIndex].
 *
 * The [VideoCapture] use case created here is handed to TerminalService so it can be
 * bound to the back camera alongside the ADAS ImageAnalysis use case.
 *
 * Alarm tagging: call [markAlarm] when a DMS/ADAS alarm fires so the current segment
 * is tagged — used later by 0x9205 alarm-filtered resource list queries.
 */
class VideoRecorder(
    private val context: Context,
    val channel: Int,
    private val index: RecordingIndex,
) {
    // Segment duration in ms — 5 minutes per standard DVR convention.
    private val segmentDurationMs = 5 * 60 * 1000L

    private val recorder = Recorder.Builder()
        .setQualitySelector(QualitySelector.from(Quality.HD))
        .build()

    /** The CameraX use case to bind to the back camera lifecycle. */
    val videoCapture: VideoCapture<Recorder> = VideoCapture.withOutput(recorder)

    private var activeRecording: Recording? = null
    private var currentEntryId: Long = 0L
    private var segmentTimer: Timer? = null

    private val recordingDir get() = File(context.filesDir, "recordings").also { it.mkdirs() }

    /** Starts continuous recording. Call from main thread after camera is bound. */
    fun start() {
        startSegment()
        scheduleNextSegment()
    }

    /** Stops the current segment. Safe to call multiple times. */
    fun stop() {
        segmentTimer?.cancel(); segmentTimer = null
        activeRecording?.stop(); activeRecording = null
        Log.i(TAG, "VideoRecorder stopped")
    }

    /** Tags the current in-progress segment with the given JT808 alarm bits. */
    fun markAlarm(alarmFlags: Long) {
        index.markAlarm(alarmFlags)
    }

    // -- Private helpers -----------------------------------------------------

    private fun startSegment() {
        val startMs  = System.currentTimeMillis()
        val fileName = "ch${channel}_" + DATE_FMT.format(Date(startMs)) + ".mp4"
        val file     = File(recordingDir, fileName)
        val entryId  = startMs
        currentEntryId = entryId

        index.add(RecordingEntry(
            id            = entryId,
            channel       = channel,
            startMs       = startMs,
            endMs         = 0L,
            filePath      = file.absolutePath,
            fileSizeBytes = 0L,
        ))

        activeRecording = recorder
            .prepareRecording(context, FileOutputOptions.Builder(file).build())
            .start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Finalize -> onSegmentFinalized(entryId, file, event)
                    else -> {}
                }
            }
        Log.i(TAG, "Recording segment started → $fileName")
    }

    private fun onSegmentFinalized(entryId: Long, file: File, event: VideoRecordEvent.Finalize) {
        val endMs = System.currentTimeMillis()
        val size  = if (event.hasError()) 0L else file.length()
        index.complete(entryId, endMs, size)
        if (event.hasError()) {
            Log.w(TAG, "Segment error code=${event.error} ${event.cause?.message}")
        } else {
            Log.i(TAG, "Segment done ${size}B → ${file.name}")
        }
    }

    private fun scheduleNextSegment() {
        segmentTimer?.cancel()
        segmentTimer = Timer("VideoRecorder", true)
        segmentTimer!!.schedule(object : TimerTask() {
            override fun run() {
                activeRecording?.stop()        // triggers onSegmentFinalized
                activeRecording = null
                startSegment()
                scheduleNextSegment()
            }
        }, segmentDurationMs)
    }

    companion object {
        private const val TAG = "VideoRecorder"
        private val DATE_FMT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }
}
