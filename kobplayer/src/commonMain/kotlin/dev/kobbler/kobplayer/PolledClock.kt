package dev.kobbler.kobplayer

import kotlin.concurrent.Volatile
import kotlin.math.abs

private const val SeekLandToleranceMs = 1_500L
// Under the app's resume confirmation window, so a seek libVLC dropped still shows up as the
// old position by the time anyone checks whether it landed.
private const val SeekLandTimeoutMs = 1_000L

/**
 * A clock read by polling, which libVLC keeps answering with the old position for a
 * moment after a seek. Readings that far from a pending seek are held back until the
 * clock gets there.
 */
class PolledClock(private val nowMs: () -> Long) {
    @Volatile private var pendingMs: Long? = null
    @Volatile private var pendingSinceMs = 0L

    fun seeked(targetMs: Long) {
        pendingSinceMs = nowMs()
        pendingMs = targetMs
    }

    fun reset() {
        pendingMs = null
    }

    fun publish(published: Long, reported: Long): Long {
        val target = pendingMs
        if (target != null) {
            val landed = abs(reported - target) <= SeekLandToleranceMs
            if (!landed && nowMs() - pendingSinceMs < SeekLandTimeoutMs) return published
            pendingMs = null
        }
        return publishedPosition(published, reported)
    }
}
