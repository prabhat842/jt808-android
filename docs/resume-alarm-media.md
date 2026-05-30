# Resume: Alarm Media Handling — COMPLETED (commit ac60486)

> **Status: Done and pushed to origin/main on 2026-05-29.**
> This document is kept for reference. No further work needed on this feature.

---

# Original Plan: Alarm Media Handling Implementation

## Context

The JT808 simulator (`/home/prabhat/jt808`) has full alarm media handling (Phase 10).
The Android terminal (`/home/prabhat/android-terminal`) is missing all of it.

The goal is to make the Android terminal autonomously capture a JPEG snapshot and
send **0x0800 MultimediaEventUpload + 0x0801 MultimediaDataUpload** whenever a JT808
alarm bit rises — without waiting for a 0x8801 platform command. This mirrors
JT808-2013 §7.9 / JT1078-2016 §6.

---

## Reference Implementation (simulator)

| File | Role |
|---|---|
| `fleet/AlarmMediaPolicy.java` | Maps alarm bit / DMS type → `Action(kind, durationSec, channelId, eventCode, label)` |
| `fleet/AlarmMediaCapture.java` | ffmpeg JPEG / MP4 capture with overlay text; falls back to Java2D synthetic |
| `fleet/Jt808MultimediaStore.java` | In-memory store; `addVideoClip`, `add` (snapshot), `payloadFor` |
| `fleet/TerminalSession.java:985` | Rising-edge detection: `risingBits = newAlarmWord & ~previousAlarmWord` |
| `fleet/TerminalSession.java:997` | `onAlarmRising(bit, snapshot)` → looks up policy → `captureAndUpload()` |
| `fleet/TerminalSession.java:1008` | `onDmsAlarm()` → `handleDmsAlarmOnEventLoop()` → sends 0x0200 then iterates actions |
| `fleet/TerminalSession.java:1075` | `captureAndUpload()` → stores item → writes 0x0800 then 0x0801 |

The simulator sends real camera media when `jt1078.capture.videoEnabled=true`;
otherwise falls back to synthetic JPEG/MP4 rendered with Java2D + ffmpeg.

---

## Gap Analysis (Android terminal)

### What exists
- `MsgId.MULTIMEDIA_EVENT_UPLOAD = 0x0800` and `MsgId.MULTIMEDIA_DATA_UPLOAD = 0x0801`
  are declared in `Jt808Messages.kt` but have **no body builders**.
- `DmsEngine.onAlarmLevelChange` fires at level ≥ 2 → calls `locationReporter.reportNow()`
  and `dvrManager.markAlarm()` but **never sends 0x0800 / 0x0801**.
- `AdasEngine.onCollisionWarning` fires → same: 0x0200 only.
- `LocationReporter` assembles `alarmFlags` each cycle but **does not track
  `previousAlarmWord`** and has **no rising-edge detection**.
- No `ImageCapture` CameraX use case is wired. There is only `ImageAnalysis` (DMS)
  and `VideoCapture` (DVR).
- `Jt808Codec.encodeFrame` builds only **single-packet frames** (body ≤ 1023 bytes).
  A JPEG snapshot is typically 5–20 KB and must be fragmented with JT808 sub-packet
  headers (§4.4.3 bit 13).

### What is missing (6 gaps)

| # | Gap | Where to fix |
|---|---|---|
| 1 | **`AlarmMediaPolicy.kt`** missing | Create new file |
| 2 | **0x0800 / 0x0801 body builders** missing | Add to `Jt808Messages.kt` |
| 3 | **Sub-packet fragmentation** missing | Add to `Jt808Codec.kt` + `Jt808TcpClientImpl.kt` |
| 4 | **Rising-edge detection** in `LocationReporter` missing | Modify `LocationReporter.kt` |
| 5 | **`ImageCapture` use case** not wired | Modify `TerminalService.kt` |
| 6 | **`captureAndUploadAlarmSnapshot()`** not wired to DMS/ADAS callbacks | Modify `TerminalService.kt` |

---

## Implementation Plan (next session)

### Step 1 — `AlarmMediaPolicy.kt` (new file)

Path: `app/src/main/java/com/example/jt808terminal/protocol/AlarmMediaPolicy.kt`

```kotlin
object AlarmMediaPolicy {
    enum class MediaKind { SNAPSHOT, VIDEO_CLIP }

    data class Action(
        val kind: MediaKind,
        val durationSec: Int,
        val channelId: Int,
        val eventCode: Int,  // 0=platform 1=alarm 2=robbery 3=collision/rollover
        val overlayLabel: String,
    )

    // JT808 alarm bit index → action (null = no media required)
    fun forAlarmBit(bitIndex: Int): Action? = when (bitIndex) {
        1  -> Action(MediaKind.SNAPSHOT,   0, 1, 1, "Overspeed")
        3  -> Action(MediaKind.SNAPSHOT,   0, 1, 1, "Risk Warning")
        13 -> Action(MediaKind.SNAPSHOT,   0, 1, 1, "Overspeed Warning")
        14 -> Action(MediaKind.VIDEO_CLIP, 10, 1, 1, "Fatigue Driving")
        29 -> Action(MediaKind.VIDEO_CLIP, 10, 1, 3, "Collision Warning")
        else -> null
    }

    // DMS alarm onset → ordered list of actions (empty = no media)
    // Mirror of simulator AlarmMediaPolicy.forDmsAlarmActions
    fun forDmsAlarmActions(dmsAlarmLevel: Int): List<Action> = when (dmsAlarmLevel) {
        DMS_FATIGUE -> listOf(
            Action(MediaKind.VIDEO_CLIP, 10, 1, 1, "Eye Closure"),
            Action(MediaKind.SNAPSHOT,   0,  1, 1, "Eye Closure"),
        )
        DMS_DISTRACTION -> listOf(Action(MediaKind.SNAPSHOT, 0, 1, 1, "Distraction"))
        DMS_PHONE       -> listOf(Action(MediaKind.SNAPSHOT, 0, 1, 1, "Phone Use"))
        DMS_SMOKING     -> listOf(Action(MediaKind.SNAPSHOT, 0, 1, 1, "Smoking"))
        DMS_NO_SEATBELT -> listOf(Action(MediaKind.SNAPSHOT, 0, 1, 1, "No Seatbelt"))
        else -> emptyList()
    }

    // DMS alarm level constants matching DmsEngine behaviour
    const val DMS_NONE        = 0
    const val DMS_FATIGUE     = 1   // PERCLOS ≥ perclosL1 (level ≥ 1)
    const val DMS_DISTRACTION = 2   // head distracted (reserved — needs future wiring)
    const val DMS_PHONE       = 3   // reserved
    const val DMS_SMOKING     = 4   // reserved
    const val DMS_NO_SEATBELT = 5   // reserved
}
```

**Note:** For VIDEO_CLIP actions on Android, capture a JPEG snapshot and send as
`mediaType=0` (image) — real MP4 extraction from DVR is out of scope here. The
`dvrManager.markAlarm()` call already tags the DVR segment; this sends the still
evidence over JT808.

---

### Step 2 — 0x0800 / 0x0801 body builders in `Jt808Messages.kt`

Add after `fileUploadComplete()`:

```kotlin
/**
 * 0x0800 Multimedia event upload — JT808-2013 §8.41 Table 52.
 * [0-3] Multimedia ID  DWORD
 * [4]   Media type     BYTE  0=image 1=audio 2=video
 * [5]   Format code    BYTE  0=JPEG 4=WMV
 * [6]   Event code     BYTE  0=platform 1=alarm 2=robbery 3=collision
 * [7]   Channel ID     BYTE
 */
fun multimediaEvent(
    mediaId: Long, mediaType: Int, formatCode: Int,
    eventCode: Int, channelId: Int,
): ByteArray = dword(mediaId.toInt()) +
    byteArrayOf(mediaType.toByte(), formatCode.toByte(),
                eventCode.toByte(), channelId.toByte())

/**
 * 0x0801 Multimedia data upload — JT808-2013 §8.42 Table 53.
 * [0-3]   Multimedia ID     DWORD
 * [4]     Media type        BYTE
 * [5]     Format code       BYTE
 * [6]     Event code        BYTE
 * [7]     Channel ID        BYTE
 * [8-35]  Location block    28 bytes (alarm+status+lat+lon+alt+speed+heading+time)
 * [36+]   Payload           BYTE[n] — JPEG bytes
 */
fun multimediaDataUpload(
    mediaId: Long, mediaType: Int, formatCode: Int,
    eventCode: Int, channelId: Int,
    locationBlock: ByteArray,   // 28-byte location info block
    payload: ByteArray,
): ByteArray = dword(mediaId.toInt()) +
    byteArrayOf(mediaType.toByte(), formatCode.toByte(),
                eventCode.toByte(), channelId.toByte()) +
    locationBlock + payload

/**
 * Builds the 28-byte location info block embedded in 0x0801 (Table 53).
 * Same layout as the fixed part of 0x0200 (Table 23), no additional items.
 */
fun locationBlock(
    alarmFlags: Long, statusFlags: Long,
    latitudeDeg: Double, longitudeDeg: Double,
    altitudeM: Int, speedKph: Double, headingDeg: Int,
    timestampMs: Long,
): ByteArray {
    val lat = Math.round(Math.abs(latitudeDeg)  * 1_000_000).toInt()
    val lon = Math.round(Math.abs(longitudeDeg) * 1_000_000).toInt()
    val spd = Math.round(speedKph * 10).toInt().coerceIn(0, 65535)
    val hdg = Math.floorMod(headingDeg, 360)
    val out = ArrayList<Byte>(28)
    writeDWordL(out, alarmFlags.toInt())
    writeDWordL(out, statusFlags.toInt())
    writeDWordL(out, lat)
    writeDWordL(out, lon)
    writeWordL(out, altitudeM)
    writeWordL(out, spd)
    writeWordL(out, hdg)
    for (b in bcdTimestampGmt8(timestampMs)) out.add(b)
    return out.toByteArray()
}
```

`writeDWordL`, `writeWordL`, `bcdTimestampGmt8`, and `dword` are already private in
the file — make `writeDWordL`, `writeWordL`, `bcdTimestampGmt8` internal or duplicate
inline. The simplest fix is to change those helpers from `private` to no modifier
(package-private equivalent in Kotlin = just remove `private`).

---

### Step 3 — Sub-packet fragmentation in `Jt808Codec.kt`

JT808-2013 §4.4.3 Table 2: when bit 13 of bodyProps = 1, a 4-byte sub-packet header
is inserted between the 12-byte header and the body:
  `[totalPackets WORD] [currentPacketNum WORD, 1-based]`

The body length field (bits 0-9) still reflects this sub-packet chunk's body size only.

Add to `Jt808Codec`:

```kotlin
const val MAX_BODY_PER_PACKET = 1023   // 10-bit body length field

/**
 * Encodes a (possibly large) body as one or more JT808 frames.
 * Returns a single frame when body ≤ MAX_BODY_PER_PACKET.
 * Returns multiple sub-packet frames (bit 13 set) otherwise.
 * All sub-packets share the same [seqNum].
 */
fun encodeFrames(
    msgId: Int, phoneNumber: String, seqNum: Int, body: ByteArray,
): List<ByteArray> {
    if (body.size <= MAX_BODY_PER_PACKET) {
        return listOf(encodeFrame(msgId, phoneNumber, seqNum, body))
    }
    val chunks = body.toList().chunked(MAX_BODY_PER_PACKET)
    val total  = chunks.size
    return chunks.mapIndexed { idx, chunk ->
        encodeSubPacketFrame(msgId, phoneNumber, seqNum,
                             chunk.toByteArray(), total, idx + 1)
    }
}

private fun encodeSubPacketFrame(
    msgId: Int, phoneNumber: String, seqNum: Int,
    chunk: ByteArray, total: Int, current: Int,
): ByteArray {
    val header = buildSubPacketHeader(msgId, phoneNumber, seqNum, chunk.size, total, current)
    val payload = header + chunk
    val checksum = payload.fold(0) { acc, b -> acc xor (b.toInt() and 0xFF) } and 0xFF
    val escaped = escape(payload + byteArrayOf(checksum.toByte()))
    return byteArrayOf(0x7E.toByte()) + escaped + byteArrayOf(0x7E.toByte())
}

private fun buildSubPacketHeader(
    msgId: Int, phoneNumber: String, seqNum: Int,
    chunkLen: Int, total: Int, current: Int,
): ByteArray {
    val h = ByteArray(16)   // 12-byte header + 4-byte sub-packet info
    h[0] = (msgId shr 8).toByte()
    h[1] = (msgId and 0xFF).toByte()
    // bodyProps: bits 0-9 = chunkLen, bit 13 = 1 (sub-package)
    val props = 0x2000 or (chunkLen and 0x03FF)
    h[2] = (props shr 8).toByte()
    h[3] = (props and 0xFF).toByte()
    writeBcd(h, 4, phoneNumber, 6)
    h[10] = (seqNum shr 8).toByte()
    h[11] = (seqNum and 0xFF).toByte()
    // Sub-packet info — JT808-2013 §4.4.3 Table 2
    h[12] = (total   shr 8).toByte(); h[13] = (total   and 0xFF).toByte()
    h[14] = (current shr 8).toByte(); h[15] = (current and 0xFF).toByte()
    return h
}
```

`writeBcd` and `escape` are already private in `Jt808Codec` — mark them `private` only
(they already are). `buildSubPacketHeader` is a new private method; `encodeFrames` is public.

---

### Step 4 — `sendMultimediaUpload` in `Jt808TcpClientImpl.kt`

Add alongside `sendCommand`:

```kotlin
/**
 * Sends 0x0800 event + 0x0801 data upload for a single multimedia item.
 * The 0x0801 body is fragmented into sub-packets when it exceeds 1023 bytes.
 * Both messages share the same seqNum within their own frame sequence.
 */
fun sendMultimediaUpload(
    eventBody: ByteArray,   // 8-byte 0x0800 body
    dataBody: ByteArray,    // 36 + N byte 0x0801 body (header + location + payload)
) {
    if (!authenticated) return
    sendFrames(MsgId.MULTIMEDIA_EVENT_UPLOAD, eventBody)
    sendFrames(MsgId.MULTIMEDIA_DATA_UPLOAD, dataBody)
}

@Synchronized
private fun sendFrames(msgId: Int, body: ByteArray) {
    val out = output ?: return
    try {
        val seq = seqGen.incrementAndGet() and 0xFFFF
        for (frame in Jt808Codec.encodeFrames(msgId, config.phoneNumber, seq, body)) {
            out.write(frame)
        }
        out.flush()
        Log.v(TAG, "TX 0x${msgId.toString(16).uppercase()} seq=$seq body=${body.size}B " +
              "(${(body.size + 1022) / 1023} packet(s))")
    } catch (e: Exception) {
        Log.w(TAG, "TX failed (0x${msgId.toString(16)}): ${e.message}")
    }
}
```

---

### Step 5 — Rising-edge detection in `LocationReporter.kt`

Add field:
```kotlin
@Volatile private var previousAlarmWord: Long = 0L
```

Add callback:
```kotlin
/** Called on the coroutine scope when a JT808 alarm bit rises for the first time. */
var onAlarmRising: ((bitIndex: Int, speedKph: Double, latDeg: Double, lonDeg: Double) -> Unit)? = null
```

At the end of `report()`, after building `alarmFlags` and before `client.sendLocationReport`:
```kotlin
// Rising-edge detection — fire after 0x0200 is queued (mirrors TerminalSession.sendLocation)
val rising = alarmFlags and previousAlarmWord.inv()
if (rising != 0L) {
    for (bit in 0..31) {
        if ((rising and (1L shl bit)) != 0L) {
            onAlarmRising?.invoke(bit, speedKph, latDeg, lonDeg)
        }
    }
}
previousAlarmWord = alarmFlags
```

Reset on disconnect (add to wherever auth is lost — or just let it drift; rising edge
on reconnect is harmless).

---

### Step 6 — ImageCapture + `captureAndUploadAlarmSnapshot` in `TerminalService.kt`

#### 6a. Add ImageCapture field
```kotlin
private var alarmImageCapture: ImageCapture? = null
private val mediaIdGen = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
```

#### 6b. Create ImageCapture in `onCreate` (alongside DmsEngine init)
```kotlin
alarmImageCapture = ImageCapture.Builder()
    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
    .build()
```

#### 6c. Bind it in `rebindAllCameras` (front camera branch)
Add `alarmImageCapture` to every `provider.bindToLifecycle(…, CameraSelector.DEFAULT_FRONT_CAMERA, …)` call.

#### 6d. Add `captureAndUploadAlarmSnapshot`
```kotlin
private fun captureAndUploadAlarmSnapshot(
    action: AlarmMediaPolicy.Action,
    alarmFlags: Long, statusFlags: Long,
    speedKph: Double, latDeg: Double, lonDeg: Double,
) {
    val capture = alarmImageCapture ?: return
    val mediaId = mediaIdGen.incrementAndGet()
    val captureTime = System.currentTimeMillis()
    val loc = locationReporter.latestLocation()   // expose getter on LocationReporter

    capture.takePicture(
        ContextCompat.getMainExecutor(this),
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val jpegBytes = imageProxyToJpeg(image)
                image.close()
                scope.launch(Dispatchers.IO) {
                    val locBlock = Jt808Messages.locationBlock(
                        alarmFlags, statusFlags,
                        latDeg, lonDeg, loc?.altitude?.toInt() ?: 0,
                        speedKph, loc?.bearing?.toInt() ?: 0, captureTime,
                    )
                    val eventBody = Jt808Messages.multimediaEvent(
                        mediaId, 0, 0, action.eventCode, action.channelId)
                    val dataBody = Jt808Messages.multimediaDataUpload(
                        mediaId, 0, 0, action.eventCode, action.channelId,
                        locBlock, jpegBytes,
                    )
                    jt808Client.sendMultimediaUpload(eventBody, dataBody)
                    Log.i(TAG, "0x0800+0x0801 sent mediaId=$mediaId label=${action.overlayLabel}")
                }
            }
            override fun onError(exc: ImageCaptureException) {
                Log.w(TAG, "Alarm snapshot capture failed: ${exc.message}")
            }
        }
    )
}

private fun imageProxyToJpeg(image: ImageProxy): ByteArray {
    val plane = image.planes[0]
    val buf   = plane.buffer
    val bytes = ByteArray(buf.remaining())
    buf.get(bytes)
    return bytes   // ImageCapture always delivers JPEG planes[0]
}
```

Add `fun latestLocation(): Location? = latest` to `LocationReporter` (expose the field).

#### 6e. Wire rising-edge callback in `onCreate`
```kotlin
locationReporter.onAlarmRising = { bit, speedKph, latDeg, lonDeg ->
    val action = AlarmMediaPolicy.forAlarmBit(bit) ?: return@onAlarmRising
    val alarmFlags = ... // need to capture current alarmFlags — see note below
    mainHandler.post {
        captureAndUploadAlarmSnapshot(action, alarmFlags, 0x01L, speedKph, latDeg, lonDeg)
    }
}
```

**Note:** `captureAndUploadAlarmSnapshot` needs `alarmFlags` and `statusFlags` for the
location block. The cleanest approach is to have `onAlarmRising` carry the full
`alarmFlags` and `statusFlags` longs, not just the bit index. Change the signature to:
```kotlin
var onAlarmRising: ((bitIndex: Int, alarmFlags: Long, statusFlags: Long,
                     speedKph: Double, latDeg: Double, lonDeg: Double) -> Unit)? = null
```

#### 6f. Wire DMS danger alarm callback
In `onCreate`, replace the existing `dmsEngine.onAlarmLevelChange` block:
```kotlin
dmsEngine.onAlarmLevelChange = { level ->
    if (level >= 2) {
        locationReporter.reportNow()
        dvrManager.markAlarm(1L shl 14)
        // New: send alarm snapshot evidence
        val actions = AlarmMediaPolicy.forDmsAlarmActions(AlarmMediaPolicy.DMS_FATIGUE)
        val loc = locationReporter.latestLocation()
        for (action in actions) {
            mainHandler.post {
                captureAndUploadAlarmSnapshot(
                    action,
                    alarmFlags  = 1L shl 14,
                    statusFlags = 0x01L,
                    speedKph    = loc?.speed?.toDouble()?.times(3.6) ?: 0.0,
                    latDeg      = loc?.latitude ?: 0.0,
                    lonDeg      = loc?.longitude ?: 0.0,
                )
            }
        }
    }
}
```

#### 6g. Wire ADAS collision callback (currently only sends 0x0200)
```kotlin
adasEngine.onCollisionWarning = {
    locationReporter.reportNow()
    dvrManager.markAlarm(1L shl 29)
    // New: send alarm snapshot evidence
    val action = AlarmMediaPolicy.forAlarmBit(29) ?: return@onCollisionWarning
    val loc = locationReporter.latestLocation()
    mainHandler.post {
        captureAndUploadAlarmSnapshot(
            action,
            alarmFlags  = 1L shl 29,
            statusFlags = 0x01L,
            speedKph    = loc?.speed?.toDouble()?.times(3.6) ?: 0.0,
            latDeg      = loc?.latitude ?: 0.0,
            lonDeg      = loc?.longitude ?: 0.0,
        )
    }
}
```

---

## File Change Summary

| File | Action | Key change |
|---|---|---|
| `protocol/AlarmMediaPolicy.kt` | **CREATE** | Alarm bit / DMS → `Action` mapping |
| `protocol/Jt808Messages.kt` | **MODIFY** | Add `multimediaEvent`, `multimediaDataUpload`, `locationBlock` |
| `protocol/Jt808Codec.kt` | **MODIFY** | Add `encodeFrames`, `encodeSubPacketFrame`, `buildSubPacketHeader` |
| `protocol/Jt808TcpClientImpl.kt` | **MODIFY** | Add `sendMultimediaUpload`, `sendFrames` |
| `protocol/LocationReporter.kt` | **MODIFY** | Add `previousAlarmWord`, `onAlarmRising` callback, rising-edge logic, `latestLocation()` getter |
| `service/TerminalService.kt` | **MODIFY** | Add `alarmImageCapture`, `captureAndUploadAlarmSnapshot`, `imageProxyToJpeg`, wire DMS/ADAS callbacks, bind `ImageCapture` in `rebindAllCameras` |

---

## Key Decisions Made

1. **JPEG-only for 0x0801** — Android can't extract a real-time video clip from the DVR
   mid-recording. VIDEO_CLIP policy actions send a JPEG snapshot (mediaType=0) instead.
   DVR already stores the segment via `dvrManager.markAlarm()`.

2. **Sub-packet MTU = 1023 bytes** — the JT808 body length field is 10 bits. JPEG
   snapshots from CameraX are typically 50–200 KB and need 50–200 sub-packets.

3. **All sub-packets of a message share one seqNum** — per JT808-2013 §4.4.3. The
   sub-packet header carries total/current packet numbers.

4. **`captureAndUploadAlarmSnapshot` runs on main thread** (CameraX `takePicture`
   requires a main-thread executor), then offloads the socket write to `Dispatchers.IO`.

5. **Rising-edge detection in `LocationReporter`** — that's where `alarmFlags` is
   assembled each cycle, matching the simulator's `sendLocation()` structure.
