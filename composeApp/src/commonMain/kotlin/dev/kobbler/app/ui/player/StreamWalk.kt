package dev.kobbler.app.ui.player

import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.StreamOption
import dev.kobbler.app.data.StreamResolveResult
import kotlinx.coroutines.CancellationException

internal sealed interface WalkResult {
    val tried: List<StreamOption>

    data class Ready(
        val option: StreamOption,
        val session: KobblerApi.StreamSession,
        override val tried: List<StreamOption>,
    ) : WalkResult

    data class Caching(
        val option: StreamOption,
        val providerId: String,
        val torrentId: String,
        override val tried: List<StreamOption>,
    ) : WalkResult

    data class Exhausted(val lastError: Throwable?, override val tried: List<StreamOption>) : WalkResult
}

/**
 * Resolve [candidates] one at a time, best first, until one is ready to play. An option
 * that needs caching is passed over, and the best-ranked of those is the answer only when
 * nothing was ready.
 *
 * Strictly in order: kobload warms the top of the list in this same order the moment it is
 * searched, so the option after a failure is usually already warm, and resolving two at
 * once only minted sessions nobody watched.
 */
internal suspend fun walkStreamOptions(
    candidates: List<StreamOption>,
    resolve: suspend (StreamOption) -> StreamResolveResult,
    onAttempt: (index: Int) -> Unit = {},
): WalkResult {
    var caching: StreamResolveResult.Caching? = null
    var lastError: Throwable? = null
    candidates.forEachIndexed { i, option ->
        onAttempt(i)
        try {
            when (val r = resolve(option)) {
                is StreamResolveResult.Ready -> return WalkResult.Ready(option, r.session, candidates.take(i + 1))
                is StreamResolveResult.Caching -> if (caching == null) caching = r
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastError = e
        }
    }
    caching?.let { return WalkResult.Caching(it.option, it.debridProviderId, it.torrentId, candidates) }
    return WalkResult.Exhausted(lastError, candidates)
}
