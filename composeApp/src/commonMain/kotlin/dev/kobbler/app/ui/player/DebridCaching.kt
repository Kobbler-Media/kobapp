package dev.kobbler.app.ui.player

import dev.kobbler.app.data.KobblerApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.TimeSource

/** Past this at 0%, the debrid service has queued the download rather than started it. */
internal const val CACHING_QUEUED_AFTER_MS = 20_000L

private const val CACHING_POLL_MS = 3_000L

internal fun cachingNote(progress: Float, elapsedMs: Long, provider: String?): String {
    val who = provider?.takeIf { it.isNotBlank() } ?: "your debrid service"
    return when {
        progress > 0f -> "Downloading on $who"
        elapsedMs >= CACHING_QUEUED_AFTER_MS -> "Queued on $who — it hasn't started downloading yet"
        else -> "Asking $who to fetch it…"
    }
}

/**
 * Follow a debrid download until the release can be resolved again, keeping the caching
 * shell's progress and note current. Null when it is ready; otherwise what went wrong.
 */
internal suspend fun GlobalPlayerState.awaitDebridCache(
    api: KobblerApi,
    providerId: String,
    torrentId: String,
    provider: String?,
    timeSource: TimeSource = TimeSource.Monotonic,
): Throwable? {
    val started = timeSource.markNow()
    cachingNote = cachingNote(0f, 0, provider)
    while (true) {
        delay(CACHING_POLL_MS)
        val status = try {
            api.streamResolveStatus(providerId, torrentId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return e
        }
        when (status.status) {
            "ready" -> return null
            "error" -> return IllegalStateException(status.message ?: "Stream failed")
            else -> {
                val progress = status.progress?.toFloat() ?: 0f
                setCachingProgress(progress)
                if (cachingProgress != null) {
                    cachingNote = cachingNote(progress, started.elapsedNow().inWholeMilliseconds, provider)
                }
            }
        }
    }
}
