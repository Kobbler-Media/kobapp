package dev.kobbler.app.ui.player

import dev.kobbler.app.data.ApiException
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.StreamOption
import dev.kobbler.app.data.StreamResolveResult
import kotlinx.coroutines.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** Every resolve, refusals included, one walk may make. */
internal const val MAX_WALK_RESOLVES = 12
internal const val MAX_WALK_MS = 30_000L

internal sealed interface WalkResult {
    val tried: List<StreamOption>

    /** Real attempts spent: resolves that were played or failed slowly, not instant refusals. */
    val attempts: Int

    data class Ready(
        val option: StreamOption,
        val session: KobblerApi.StreamSession,
        override val tried: List<StreamOption>,
        override val attempts: Int,
    ) : WalkResult

    data class Caching(
        val option: StreamOption,
        val providerId: String,
        val torrentId: String,
        override val tried: List<StreamOption>,
        override val attempts: Int,
    ) : WalkResult

    data class Exhausted(
        val lastError: Throwable?,
        override val tried: List<StreamOption>,
        override val attempts: Int,
    ) : WalkResult
}

private enum class Refusal { Skip, Stop, Attempt }

/**
 * A refusal the debrid service answers in milliseconds costs the viewer nothing, so it is
 * not charged to the budget. A rate limit or a rejected key is the same answer for every
 * option left, and asking again only makes the limit last longer.
 */
private fun refusalOf(e: Exception): Refusal = when ((e as? ApiException)?.reason) {
    "blocked", "not_video" -> Refusal.Skip
    "rate_limited", "auth_failed" -> Refusal.Stop
    else -> Refusal.Attempt
}

/**
 * Resolve [candidates] one at a time, best first, until one is ready to play. An option
 * that needs caching is passed over, and the best-ranked of those is the answer only when
 * nothing was ready; once one is in hand, uncached options are not resolved at all — each
 * resolve of one queues a download on the debrid service.
 *
 * Strictly in order: kobload warms the top of the list in this same order the moment it is
 * searched, so the option after a failure is usually already warm, and resolving two at
 * once only minted sessions nobody watched.
 */
internal suspend fun walkStreamOptions(
    candidates: List<StreamOption>,
    resolve: suspend (StreamOption) -> StreamResolveResult,
    attempts: Int = MAX_AUTO_FALLBACK + 1,
    resolves: Int = MAX_WALK_RESOLVES,
    timeSource: TimeSource = TimeSource.Monotonic,
    onAttemptFailed: () -> Unit = {},
): WalkResult {
    val started = timeSource.markNow()
    val tried = mutableListOf<StreamOption>()
    var spent = 0
    var caching: StreamResolveResult.Caching? = null
    var lastError: Throwable? = null
    for (option in candidates) {
        if (spent >= attempts || tried.size >= resolves) break
        if (started.elapsedNow() >= MAX_WALK_MS.milliseconds) break
        if (caching != null && option.availability == AVAILABILITY_UNCACHED) continue
        tried += option
        try {
            when (val r = resolve(option)) {
                is StreamResolveResult.Ready -> return WalkResult.Ready(option, r.session, tried, spent + 1)
                is StreamResolveResult.Caching -> if (caching == null) caching = r
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastError = e
            when (refusalOf(e)) {
                Refusal.Skip -> {}
                Refusal.Stop -> break
                Refusal.Attempt -> {
                    spent++
                    onAttemptFailed()
                }
            }
        }
    }
    caching?.let { return WalkResult.Caching(it.option, it.debridProviderId, it.torrentId, tried, spent) }
    return WalkResult.Exhausted(lastError, tried, spent)
}
