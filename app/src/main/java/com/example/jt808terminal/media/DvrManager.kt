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
import com.example.jt808terminal.db.DvrSegmentDao
import com.example.jt808terminal.db.DvrSegmentEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Timer
import java.util.TimerTask

/**
 * DVR manager — 2-hour rolling ring of 5-minute MP4 segments per channel.
 *
 * Rolling-ring rule (normal segments):
 *   - Each channel keeps at most [MAX_NORMAL_SEGMENTS] complete non-alarm segments.
 *   - When the (MAX_NORMAL_SEGMENTS + 1)th segment completes, the oldest is deleted
 *     from disk and from the database, maintaining the 2-hour window.
 *
 * Alarm segments:
 *   - When [markAlarm] is called the active segment is tagged as an alarm clip in
 *     the database.  Alarm clips are exempt from the rolling-ring eviction but are
 *     subject to a separate 24-hour age limit enforced by [StorageManager].
 *
 * The [VideoCapture] use case is created once and handed to TerminalService for
 * binding to the back-camera lifecycle (same as Phase 7 VideoRecorder).
 */
class DvrManager(
    private val context: Context,
    val channel: Int,
    private val dao: DvrSegmentDao,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "DvrManager"
        /** 24 × 5-min segments = 2-hour rolling window. */
        const val MAX_NORMAL_SEGMENTS = 24
        private const val SEGMENT_DURATION_MS = 5 * 60 * 1000L
        private val DATE_FMT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }

    private val recorder = Recorder.Builder()
        .setQualitySelector(QualitySelector.from(Quality.HD))
        .build()

    /** Bind this to the back camera in TerminalService.rebindAllCameras(). */
    val videoCapture: VideoCapture<Recorder> = VideoCapture.withOutput(recorder)

    private var activeRecording: Recording? = null
    private var activeSegmentId: Long = 0L
    private var segmentTimer: Timer? = null

    private val dvrDir: File
        get() = File(context.filesDir, "dvr/ch$channel").also { it.mkdirs() }

    // -------------------------------------------------------------------------

    /** Starts continuous recording. Must be called on the main thread after camera is bound. */
    fun start() {
        startNewSegment()
        scheduleNext()
        Log.i(TAG, "DVR started ch=$channel")
    }

    fun stop() {
        segmentTimer?.cancel(); segmentTimer = null
        activeRecording?.stop(); activeRecording = null
        Log.i(TAG, "DVR stopped ch=$channel")
    }

    /**
     * Marks the currently active segment as an alarm clip.
     * Called by TerminalService when DMS/ADAS/BSD alarm fires.
     */
    fun markAlarm(alarmFlags: Long) {
        val id = activeSegmentId
        if (id == 0L) return
        scope.launch(Dispatchers.IO) {
            dao.addAlarmFlags(id, alarmFlags)
            dao.markAsAlarmClip(id)
            Log.d(TAG, "Segment $id tagged as alarm clip flags=0x${alarmFlags.toString(16)}")
        }
    }

    // -------------------------------------------------------------------------

    private fun startNewSegment() {
        val startMs  = System.currentTimeMillis()
        val fileName = "ch${channel}_${DATE_FMT.format(Date(startMs))}.mp4"
        val file     = File(dvrDir, fileName)
        val id       = startMs
        activeSegmentId = id

        scope.launch(Dispatchers.IO) {
            dao.insert(DvrSegmentEntity(
                id            = id,
                channel       = channel,
                startMs       = startMs,
                endMs         = 0L,
                filePath      = file.absolutePath,
                fileSizeBytes = 0L,
            ))
        }

        activeRecording = recorder
            .prepareRecording(context, FileOutputOptions.Builder(file).build())
            .start(ContextCompat.getMainExecutor(context)) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    onSegmentFinalized(id, file, event)
                }
            }
        Log.i(TAG, "DVR segment started → $fileName")
    }

    private fun onSegmentFinalized(id: Long, file: File, event: VideoRecordEvent.Finalize) {
        val endMs = System.currentTimeMillis()
        val size  = if (event.hasError()) 0L else file.length()
        if (event.hasError()) Log.w(TAG, "Segment error code=${event.error}")
        scope.launch(Dispatchers.IO) {
            dao.complete(id, endMs, size)
            evictIfNeeded()
        }
    }

    /**
     * Enforces the rolling-ring limit: if there are now more than [MAX_NORMAL_SEGMENTS]
     * complete normal segments for this channel, delete the oldest until within limit.
     */
    private suspend fun evictIfNeeded() {
        val count = dao.completeNormalCount(channel)
        if (count <= MAX_NORMAL_SEGMENTS) return
        val oldest = dao.getNormalSegmentsOldestFirst(channel)
        var excess = count - MAX_NORMAL_SEGMENTS
        for (seg in oldest) {
            if (excess <= 0) break
            File(seg.filePath).delete()
            dao.delete(seg.id)
            excess--
            Log.i(TAG, "DVR evicted oldest segment ${seg.filePath}")
        }
    }

    private fun scheduleNext() {
        segmentTimer?.cancel()
        segmentTimer = Timer("DvrSegment", true)
        segmentTimer!!.schedule(object : TimerTask() {
            override fun run() {
                activeRecording?.stop()
                activeRecording = null
                startNewSegment()
                scheduleNext()
            }
        }, SEGMENT_DURATION_MS)
    }
}
