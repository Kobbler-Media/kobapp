package dev.kobbler.app.ui.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import dev.kobbler.app.data.DiscoverDetail
import dev.kobbler.app.data.DiscoverEpisodeRef
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.StreamOption
import dev.kobbler.app.data.StreamResolveResult
import dev.kobbler.app.data.StreamSearchResult
import dev.kobbler.app.ui.navigation.Destination
import dev.kobbler.app.ui.player.GlobalPlayerState
import dev.kobbler.app.ui.player.MAX_AUTO_FALLBACK
import dev.kobbler.app.ui.player.applyWarmReady
import dev.kobbler.kobplayer.Player
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal data class Caching(
    val providerId: String,
    val torrentId: String,
    val option: StreamOption,
    val episode: DiscoverEpisodeRef?,
)

/**
 * Debrid playback for a title that is not in the library. A null episode is the movie.
 *
 * Play goes straight to the best-ranked option, as the library pages do; the option list
 * is only ever opened on request, or when there is nothing to play.
 */
@Stable
internal class DiscoverStreams(
    private val api: KobblerApi,
    private val gps: GlobalPlayerState,
    private val scope: CoroutineScope,
    private val detail: () -> DiscoverDetail,
    private val player: () -> Player?,
    private val onViewInLibrary: (mediaType: String, libraryId: String) -> Unit,
) {
    var result by mutableStateOf<StreamSearchResult?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var showOptions by mutableStateOf(false)
    var caching by mutableStateOf<Caching?>(null)
        private set
    private var pickedFor: DiscoverEpisodeRef? = null
    private var search: Pair<DiscoverEpisodeRef?, Deferred<StreamSearchResult>>? = null

    private fun searchFor(episode: DiscoverEpisodeRef?): Deferred<StreamSearchResult> {
        search?.takeIf { it.first == episode }?.let { return it.second }
        result = null
        loading = true
        val pending = scope.async {
            runCatching { api.streamSearch(discover = detail(), discoverEpisode = episode) }
                .getOrElse { StreamSearchResult(emptyList(), listOf("Search failed")) }
                .also {
                    result = it
                    loading = false
                }
        }
        search = episode to pending
        return pending
    }

    fun prefetch(episode: DiscoverEpisodeRef?) {
        searchFor(episode)
    }

    fun pick(episode: DiscoverEpisodeRef?) {
        pickedFor = episode
        showOptions = true
        searchFor(episode)
    }

    fun play(episode: DiscoverEpisodeRef?, chosen: StreamOption? = null) {
        showOptions = false
        val d = detail()
        var job: Job? = null
        gps.beginPreparing(
            id = "discover:${d.provider}:${d.providerId}:${episode?.season}:${episode?.episode}",
            newTitle = d.title,
            newSubtitle = episode?.label,
            newPosterUrl = d.posterUrl,
            note = "Not in your library — searching for a stream.",
            onCancel = {
                job?.cancel()
                gps.cancelPreparing()
            },
        )
        job = scope.launch {
            val options = searchFor(episode).await().options
            if (options.isEmpty()) {
                gps.cancelPreparing()
                pick(episode)
                return@launch
            }
            resolveWithFallback(options, chosen ?: options.first(), episode)
            if (gps.mediaId == null && caching == null) gps.cancelPreparing()
        }
    }

    fun playPicked(option: StreamOption) = play(pickedFor, option)

    private fun cancelCaching() {
        caching = null
        gps.endCachingPreparation()
        gps.isFullscreen = false
    }

    private fun failureContext(vararg extra: String) =
        listOf("Discover ${detail().mediaType}: ${detail().title}") + extra

    private suspend fun resolveWithFallback(
        options: List<StreamOption>,
        option: StreamOption,
        episode: DiscoverEpisodeRef?,
        attempt: Int = 0,
    ) {
        val next = options.getOrNull(options.indexOfFirst { it.id == option.id } + 1)
            ?.takeIf { attempt < MAX_AUTO_FALLBACK }
        try {
            when (val r = resolve(option, episode)) {
                is StreamResolveResult.Ready -> play(r.session, episode)
                is StreamResolveResult.Caching -> {
                    // The next option in order, not the next verified-cached one. kobload
                    // ranks the list by real availability and warms the top WARM_TOP_N
                    // precisely because the client walks it in order, and `cached` means a
                    // first-hand probe only — so filtering on it passed over better-ranked
                    // options whose availability was merely unprobed, and skipped every
                    // usenet option outright, since that path reports cached = false.
                    if (next != null) {
                        resolveWithFallback(options, next, episode, attempt + 1)
                    } else {
                        caching = Caching(r.debridProviderId, r.torrentId, r.option, episode)
                        gps.beginCachingPreparation(title = detail().title, onCancel = ::cancelCaching)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (next != null) resolveWithFallback(options, next, episode, attempt + 1)
            else gps.failWith(e, failureContext("All debrid stream options failed"))
        }
    }

    private suspend fun resolve(option: StreamOption, episode: DiscoverEpisodeRef?) =
        api.streamResolve(
            option = option,
            mediaType = detail().mediaType,
            autoCreate = detail(),
            autoCreateEpisode = episode,
        )

    private suspend fun play(session: KobblerApi.StreamSession, episode: DiscoverEpisodeRef?) {
        val mediaId = session.mediaId ?: return
        val d = detail()
        val (libraryType, libraryId) =
            if (episode == null) d.mediaType to mediaId else "tv" to api.getShowIdForEpisode(mediaId)
        player()?.let { p ->
            gps.load(
                player = p, api = api,
                type = if (episode == null) d.mediaType else "episode", id = mediaId,
                streamUrl = session.url, newSessionId = session.sessionId,
                newTitle = d.title,
                newSubtitle = episode?.label ?: listOfNotNull(
                    d.year?.toString(),
                    d.runtime?.let { "${it / 60}h ${it % 60}m" },
                ).joinToString(" • "),
                newPosterUrl = d.posterUrl, newLogoUrl = null,
                destination = if (episode == null) Destination.MovieDetail(libraryId) else Destination.TvShowDetail(libraryId),
                session = session,
            )
            gps.playUnmuted(p)
            gps.isFullscreen = true
        }
        onViewInLibrary(libraryType, libraryId)
    }

    suspend fun pollCaching() {
        val c = caching ?: return
        while (true) {
            delay(3000)
            try {
                val status = api.streamResolveStatus(c.providerId, c.torrentId)
                when (status.status) {
                    "ready" -> {
                        val r = resolve(c.option, c.episode)
                        caching = null
                        gps.endCachingPreparation()
                        if (r is StreamResolveResult.Ready) play(r.session, c.episode) else gps.isFullscreen = false
                        return
                    }
                    "downloading" -> gps.setCachingProgress(status.progress?.toFloat() ?: 0f)
                    "error" -> {
                        caching = null
                        gps.failWith(IllegalStateException(status.message ?: "Stream failed"), failureContext())
                        return
                    }
                }
            } catch (e: Exception) {
                caching = null
                gps.failWith(e, failureContext())
                return
            }
        }
    }

    // Warming finishes after the search returns, so an option listed as unknown can
    // become verified-instant while the picker is open.
    suspend fun foldWarmReady() {
        api.libraryEvents().collect { frame ->
            if (frame.kind != "stream_warm_ready") return@collect
            val hash = frame.data["info_hash"]?.jsonPrimitive?.contentOrNull ?: return@collect
            val providerId = frame.data["debrid_provider_id"]?.jsonPrimitive?.contentOrNull ?: return@collect
            val fid = frame.data["file_id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            result = applyWarmReady(result, hash, fid, providerId)
        }
    }
}

private val DiscoverEpisodeRef.label get() = "S$season E$episode"

@Composable
internal fun rememberDiscoverStreams(
    detail: DiscoverDetail,
    api: KobblerApi,
    gps: GlobalPlayerState,
    player: Player?,
    onViewInLibrary: (mediaType: String, libraryId: String) -> Unit,
): DiscoverStreams {
    val scope = rememberCoroutineScope()
    val currentDetail by rememberUpdatedState(detail)
    val currentPlayer by rememberUpdatedState(player)
    val currentOnView by rememberUpdatedState(onViewInLibrary)
    val streams = remember(api, gps) {
        DiscoverStreams(api, gps, scope, { currentDetail }, { currentPlayer }, { t, id -> currentOnView(t, id) })
    }
    LaunchedEffect(streams.caching) { streams.pollCaching() }
    // Only while the picker is open: this holds a connection.
    LaunchedEffect(streams.showOptions) { if (streams.showOptions) streams.foldWarmReady() }
    return streams
}
