package com.example.jt808terminal.dms

// PERCLOS (Percentage of Eye Closure) over a rolling 60-second window.
// Thread-affine: all calls must come from the DMS HandlerThread.
// Reference: NHTSA "Drowsy Driver Detection and Warning System" technical guidelines.
class PerclosTracker(private val windowMs: Long = 60_000L) {

    private data class Sample(val ts: Long, val closed: Boolean)
    private val samples = ArrayDeque<Sample>()

    fun record(eyesClosed: Boolean, ts: Long = System.currentTimeMillis()) {
        samples.addLast(Sample(ts, eyesClosed))
        val cutoff = ts - windowMs
        while (samples.isNotEmpty() && samples.first().ts < cutoff) samples.removeFirst()
    }

    // Returns 0.0-1.0 fraction of samples where eyes were closed.
    fun perclos(): Float {
        val n = samples.size
        if (n == 0) return 0f
        return samples.count { it.closed }.toFloat() / n
    }

    fun reset() = samples.clear()
    fun sampleCount() = samples.size
}
