package dev.kobbler.app.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.Movie
import dev.kobbler.app.data.Prewarm
import dev.kobbler.app.data.StreamOption
import dev.kobbler.app.data.StreamTarget
import dev.kobbler.app.data.TvShow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal class InstantStart(val option: StreamOption, val session: KobblerApi.StreamSession)

private const val SETTLE_MS = 1_000L

// The server lets an untouched prewarm go after about five minutes.
private const val TOUCH_MS = 60_000L

/**
 * Debrid streams the server has been asked to have ready before anyone presses Play, by
 * title. Whoever starts that title claims it; whatever nobody claims is released.
 */
class InstantStreams {
    private val held = mutableMapOf<String, Prewarm>()

    internal suspend fun warm(api: KobblerApi, target: StreamTarget, startTime: Int? = null) {
        val key = target.key
        held[key]?.let { if (api.touchPrewarm(it.id)) return else held.remove(key) }
        val warmed = try {
            api.prewarmStream(target, startTime)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return
        held[key] = warmed
    }

    internal suspend fun claim(api: KobblerApi, target: StreamTarget): InstantStart? {
        val prewarm = held.remove(target.key) ?: return null
        val option = prewarm.option
        if (option == null) {
            api.releasePrewarm(prewarm.id)
            return null
        }
        return try {
            InstantStart(option, api.claimPrewarm(prewarm.id, autoCreated = target.discover != null))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    internal suspend fun release(api: KobblerApi, target: StreamTarget) {
        val prewarm = held.remove(target.key) ?: return
        api.releasePrewarm(prewarm.id)
    }

    /**
     * Keep [target] warm until cancelled, then let it go. Nothing is asked while [busy] —
     * a player already on this title has no use for a second copy of it.
     */
    internal suspend fun keepWarm(
        api: KobblerApi,
        target: StreamTarget,
        startTime: () -> Int?,
        busy: () -> Boolean,
        settleMs: Long = SETTLE_MS,
        touchMs: Long = TOUCH_MS,
    ) {
        try {
            delay(settleMs)
            while (true) {
                if (!busy()) warm(api, target, startTime())
                delay(touchMs)
            }
        } finally {
            withContext(NonCancellable) { release(api, target) }
        }
    }
}

@Composable
internal fun WarmStream(gps: GlobalPlayerState, api: KobblerApi, target: StreamTarget?, startTime: Int? = null) {
    val currentStart by rememberUpdatedState(startTime)
    LaunchedEffect(target?.key) {
        target ?: return@LaunchedEffect
        gps.instant.keepWarm(api, target, startTime = { currentStart }, busy = { gps.isActive })
    }
}

internal fun nextEpisodeDue(positionMs: Long, durationMs: Long): Boolean =
    durationMs > 0 && positionMs >= durationMs * 7 / 10

internal fun Movie.instantTarget(): StreamTarget? =
    takeIf { filePath == null }?.let { StreamTarget.movie(id, rememberedAudioTitle("movie", id, null)) }

internal fun nextEpisodeWarmTarget(show: TvShow, playingEpisodeId: String?): StreamTarget? =
    neighbourEpisode(show, playingEpisodeId, 1)?.third
        ?.takeIf { it.filePath == null }
        ?.id
        ?.let { StreamTarget.episode(it, rememberedAudioTitle("episode", it, show.id)) }

internal fun TvShow.instantTarget(): StreamTarget? = continueEpisode
    ?.let { seasons.getOrNull(it.seasonIdx)?.episodes?.getOrNull(it.episodeIdx) }
    ?.takeIf { it.filePath == null }
    ?.id
    ?.let { StreamTarget.episode(it, rememberedAudioTitle("episode", it, id)) }
