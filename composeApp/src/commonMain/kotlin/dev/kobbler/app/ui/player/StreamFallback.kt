package dev.kobbler.app.ui.player

import dev.kobbler.app.data.LibraryEventFrame
import dev.kobbler.app.data.StreamOption
import dev.kobbler.app.data.StreamSearchResult
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * How many real attempts auto-play makes before giving up and showing caching progress.
 * Instant refusals are not attempts; see [walkStreamOptions].
 *
 * Keep at or below kobload's `WARM_TOP_N` (`warm_cache.rs`, currently 8): only the top
 * N are submitted for cache warming, so an option past that depth was never probed and
 * falling onto it trades one cold wait for another.
 */
internal const val MAX_AUTO_FALLBACK = 5

internal const val PLAYBACK_FAILED = "failed"
internal const val PLAYBACK_WORKED = "worked"

internal const val AVAILABILITY_READY = "ready"
internal const val AVAILABILITY_UNCACHED = "uncached"

internal val StreamOption.isInstant: Boolean get() = cached || availability == AVAILABILITY_READY

/**
 * The options an automatic walk will try, best first: the one asked for, then what is
 * ranked after it in the server's order. An option this server has watched fail goes
 * behind every other — the server sinks those already, but [skip] only knows what failed
 * in this sitting — and one the debrid service does not hold goes last of all, since the
 * best it can do is start a download.
 */
internal fun fallbackCandidates(
    options: List<StreamOption>,
    from: StreamOption?,
    skip: Set<String> = emptySet(),
): List<StreamOption> {
    val start = from?.let { f -> options.indexOfFirst { it.id == f.id }.coerceAtLeast(0) } ?: 0
    val rest = options.drop(start).filter { it.id !in skip && it.id != from?.id && !it.reportedBad }
    val (uncached, held) = rest.partition { it.availability == AVAILABILITY_UNCACHED }
    val (failed, fresh) = held.partition { it.playback == PLAYBACK_FAILED }
    return listOfNotNull(from) + fresh + failed + uncached
}

/**
 * Fold a `stream_warm_ready` event into a search result, marking the option that just
 * finished warming as verified-instant so the picker stops calling it unknown.
 *
 * Identity is (release key, file id, provider): a season pack appears once per episode
 * file, so the key alone can land on a sibling entry.
 */
internal fun applyWarmReady(result: StreamSearchResult?, frame: LibraryEventFrame): StreamSearchResult? {
    if (result == null || frame.kind != "stream_warm_ready") return result
    val key = frame.data["release_key"]?.jsonPrimitive?.contentOrNull ?: return result
    val providerId = frame.data["debrid_provider_id"]?.jsonPrimitive?.contentOrNull ?: return result
    val fileId = frame.data["file_id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
    var changed = false
    val options = result.options.map { opt ->
        if (!opt.cached &&
            opt.releaseKey == key &&
            opt.debridProviderId == providerId &&
            opt.fileId == fileId
        ) {
            changed = true
            opt.copy(cached = true)
        } else {
            opt
        }
    }
    return if (changed) result.copy(options = options) else result
}
