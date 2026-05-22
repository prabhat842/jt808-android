package com.example.jt808terminal.media

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Thread-safe in-memory index of local video recordings.
 * Persisted to JSON at [Context.filesDir]/recordings/index.json on every mutation.
 *
 * Used to answer 0x9205 resource list queries per JT/T 1078-2016 §5.6.1 Table 21.
 */
class RecordingIndex(context: Context) {

    private val indexFile = File(context.filesDir, "recordings/index.json")
    private val entries = mutableListOf<RecordingEntry>()

    init {
        indexFile.parentFile?.mkdirs()
        load()
    }

    @Synchronized
    fun add(entry: RecordingEntry) {
        entries.add(entry)
        save()
    }

    /** Finishes an in-progress recording — sets end time and file size. */
    @Synchronized
    fun complete(id: Long, endMs: Long, fileSizeBytes: Long) {
        val idx = entries.indexOfFirst { it.id == id }
        if (idx < 0) return
        entries[idx] = entries[idx].copy(endMs = endMs, fileSizeBytes = fileSizeBytes)
        save()
        Log.d(TAG, "Recording $id finalised ${fileSizeBytes}B")
    }

    /** Sets additional alarm bits on the most recently started recording. */
    @Synchronized
    fun markAlarm(alarmFlags: Long) {
        val idx = entries.indexOfLast { !it.isComplete }
        if (idx < 0) return
        entries[idx] = entries[idx].copy(alarmFlags = entries[idx].alarmFlags or alarmFlags)
        save()
    }

    /**
     * Returns all complete recordings matching the 0x9205 query filters.
     *
     * @param channel  logical channel; 0 = all channels
     * @param startMs  epoch ms lower bound; 0 = no lower bound
     * @param endMs    epoch ms upper bound; 0 = no upper bound
     * @param alarmMask JT808 alarm bit mask; 0 = no alarm filter
     * @param resourceType 0=AV, 1=audio, 2=video, 3=video-or-AV; match any when 3
     */
    @Synchronized
    fun query(
        channel: Int,
        startMs: Long,
        endMs: Long,
        alarmMask: Long,
        resourceType: Int,
    ): List<RecordingEntry> = entries.filter { e ->
        e.isComplete &&
        (channel == 0 || e.channel == channel) &&
        (startMs == 0L || e.endMs >= startMs) &&
        (endMs == 0L || e.startMs <= endMs) &&
        (alarmMask == 0L || (e.alarmFlags and alarmMask) != 0L) &&
        (resourceType == 3 || e.resourceType == resourceType || resourceType == 0)
    }

    @Synchronized
    fun getAll(): List<RecordingEntry> = entries.toList()

    @Synchronized
    fun findById(id: Long): RecordingEntry? = entries.firstOrNull { it.id == id }

    @Synchronized
    fun remove(id: Long) {
        if (entries.removeIf { it.id == id }) save()
    }

    // -- Persistence ---------------------------------------------------------

    private fun save() {
        try {
            val arr = JSONArray()
            for (e in entries) arr.put(entryToJson(e))
            indexFile.writeText(arr.toString())
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to save index: ${ex.message}")
        }
    }

    private fun load() {
        if (!indexFile.exists()) return
        try {
            val arr = JSONArray(indexFile.readText())
            for (i in 0 until arr.length()) {
                entries.add(jsonToEntry(arr.getJSONObject(i)))
            }
            Log.i(TAG, "Loaded ${entries.size} recording(s) from index")
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to load index: ${ex.message}")
        }
    }

    private fun entryToJson(e: RecordingEntry) = JSONObject().apply {
        put("id", e.id); put("channel", e.channel)
        put("startMs", e.startMs); put("endMs", e.endMs)
        put("filePath", e.filePath); put("fileSizeBytes", e.fileSizeBytes)
        put("alarmFlags", e.alarmFlags)
        put("resourceType", e.resourceType); put("streamType", e.streamType)
        put("memoryType", e.memoryType)
    }

    private fun jsonToEntry(j: JSONObject) = RecordingEntry(
        id            = j.getLong("id"),
        channel       = j.getInt("channel"),
        startMs       = j.getLong("startMs"),
        endMs         = j.getLong("endMs"),
        filePath      = j.getString("filePath"),
        fileSizeBytes = j.getLong("fileSizeBytes"),
        alarmFlags    = j.optLong("alarmFlags", 0L),
        resourceType  = j.optInt("resourceType", 2),
        streamType    = j.optInt("streamType", 1),
        memoryType    = j.optInt("memoryType", 1),
    )

    companion object {
        private const val TAG = "RecordingIndex"
    }
}
