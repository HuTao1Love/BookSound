package com.zyagodin.booksound.core.playback

/**
 * Recognizes a deliberate shake in a stream of accelerometer readings: several strong jolts in
 * quick succession. A single bump (putting the phone down, turning over in bed) is not enough,
 * and after a shake the gesture rests for a moment so one long shake counts once.
 */
class ShakeGesture(
    private val thresholdG: Float = 2.3f,
    private val peaksNeeded: Int = 3,
    private val windowMs: Long = 1_200,
    private val minPeakGapMs: Long = 120,
    private val cooldownMs: Long = 2_000,
) {
    private var windowStart = Long.MIN_VALUE
    private var lastPeak = Long.MIN_VALUE
    private var peaks = 0
    private var lastShake = Long.MIN_VALUE

    /** Feeds one reading ([gForce] = acceleration / g); returns true when it completes a shake. */
    fun onSample(timeMs: Long, gForce: Float): Boolean {
        if (gForce < thresholdG) return false
        if (lastShake != Long.MIN_VALUE && timeMs - lastShake < cooldownMs) return false
        // Consecutive readings of the same jolt count once.
        if (lastPeak != Long.MIN_VALUE && timeMs - lastPeak < minPeakGapMs) return false
        if (windowStart == Long.MIN_VALUE || timeMs - windowStart > windowMs) {
            windowStart = timeMs
            peaks = 0
        }
        lastPeak = timeMs
        peaks++
        if (peaks < peaksNeeded) return false
        lastShake = timeMs
        reset()
        return true
    }

    fun reset() {
        windowStart = Long.MIN_VALUE
        lastPeak = Long.MIN_VALUE
        peaks = 0
    }
}
