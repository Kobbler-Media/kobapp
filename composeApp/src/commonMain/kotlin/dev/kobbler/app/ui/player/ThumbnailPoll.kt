package dev.kobbler.app.ui.player

import dev.kobbler.app.data.ThumbnailsProbe
import kotlinx.coroutines.delay

enum class ThumbnailFetch { Changed, Unchanged, Failed, Gone }

private const val BasePollMs = 15_000L
private const val MaxBackoffSteps = 3

class ThumbnailPoll(private val live: Boolean) {
    private var unchanged = 0
    private var idle = 0

    fun after(fetch: ThumbnailFetch): Long? {
        when (fetch) {
            ThumbnailFetch.Gone -> return null
            ThumbnailFetch.Changed -> {
                unchanged = 0
                idle = 0
            }
            ThumbnailFetch.Unchanged -> {
                unchanged++
                idle++
            }
            ThumbnailFetch.Failed -> idle++
        }
        if (!live && unchanged >= 2) return null
        return BasePollMs shl idle.coerceAtMost(MaxBackoffSteps)
    }
}

/** Wait for a session's thumbnails to exist. Null once the session is gone or the wait runs out. */
suspend fun awaitThumbnails(
    probe: suspend () -> ThumbnailsProbe,
    intervalMs: Long = 5_000,
    attempts: Int = 60,
): String? {
    repeat(attempts) {
        delay(intervalMs)
        when (val p = probe()) {
            is ThumbnailsProbe.Ready -> return p.url
            ThumbnailsProbe.Gone -> return null
            ThumbnailsProbe.Pending -> {}
        }
    }
    return null
}
