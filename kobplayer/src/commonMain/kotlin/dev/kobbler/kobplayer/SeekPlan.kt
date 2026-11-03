package dev.kobbler.kobplayer

sealed interface SeekPlan {
    data class Local(val elementMs: Long) : SeekPlan
    data class Reopen(val targetMs: Long) : SeekPlan
}

/**
 * A growing transcode playlist only lists what the server has produced so far, so a seek
 * outside it has to reopen the stream there rather than wait for segments that may be an
 * hour of encoding away.
 */
fun planSeek(targetMs: Long, offsetMs: Long, producedEndMs: Long, canReopen: Boolean): SeekPlan {
    val local = targetMs - offsetMs
    return when {
        canReopen && (local < 0 || local > producedEndMs) -> SeekPlan.Reopen(targetMs)
        else -> SeekPlan.Local(local.coerceAtLeast(0))
    }
}
