package dev.kobbler.app.ui.player

/**
 * How many options auto-play tries before giving up and showing caching progress.
 *
 * Keep at or below kobload's `WARM_TOP_N` (`warm_cache.rs`, currently 8): only the top
 * N are submitted for cache warming, so an option past that depth was never probed and
 * falling onto it trades one cold wait for another.
 */
internal const val MAX_AUTO_FALLBACK = 5

/**
 * What to tell the viewer while auto-play walks the option list. The first attempt is
 * the caller's own message — "searching for a stream" or similar — because until one
 * fails there is nothing to count.
 */
internal fun fallbackNote(attempt: Int): String? =
    if (attempt <= 0) null else "Trying stream ${attempt + 1} of ${MAX_AUTO_FALLBACK + 1}…"

/**
 * Fold a `stream_warm_ready` event into a search result, marking the option that just
 * finished warming as verified-instant so the picker stops calling it unknown.
 *
 * Identity is (info_hash, file id, provider): a season pack appears once per episode
 * file, so the hash alone can land on a sibling entry.
 */
internal fun applyWarmReady(
    result: dev.kobbler.app.data.StreamSearchResult?,
    infoHash: String,
    fileId: Int?,
    debridProviderId: String,
): dev.kobbler.app.data.StreamSearchResult? {
    if (result == null) return null
    var changed = false
    val options = result.options.map { opt ->
        if (!opt.cached &&
            opt.infoHash == infoHash &&
            opt.debridProviderId == debridProviderId &&
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
