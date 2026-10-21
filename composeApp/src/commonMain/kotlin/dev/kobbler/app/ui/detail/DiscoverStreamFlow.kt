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
import dev.kobbler.app.data.isWantedStatus
import dev.kobbler.app.data.StreamSearchResult
import dev.kobbler.app.data.StreamTarget
import dev.kobbler.app.ui.navigation.Destination
import dev.kobbler.app.ui.player.GlobalPlayerState
import dev.kobbler.app.ui.player.DebridPlayback
import dev.kobbler.app.ui.player.InstantStart
import dev.kobbler.app.ui.player.resumeRequestSeconds
import dev.kobbler.app.ui.player.resumeSeekSeconds
import dev.kobbler.app.ui.player.WantAction
import dev.kobbler.app.ui.player.applyWarmReady
import dev.kobbler.app.ui.player.awaitDebridCache
import dev.kobbler.kobplayer.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal data class Caching(
    val providerId: String,
    val torrentId: String,
    val option: StreamOption,
    val episode: DiscoverEpisodeRef?,
    val walk: DebridPlayback,
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
            newBackdropUrl = d.backdropUrl,
            note = FINDING_NOTE,
            onCancel = {
                job?.cancel()
                gps.cancelPreparing()
            },
        )
        job = scope.launch {
            val claim: (suspend () -> InstantStart?)? = if (chosen == null) {
                { gps.instant.claim(api, StreamTarget.discover(d, episode)) }
            } else null
            walkFor(episode, claim).start(chosen, resumeSec = 0)
            if (gps.mediaId == null && caching == null && gps.loadingError == null) gps.cancelPreparing()
        }
    }

    fun playPicked(option: StreamOption) = play(pickedFor, option)

    private fun cancelCaching() {
        caching = null
        gps.endCachingPreparation()
        gps.isFullscreen = false
    }

    private fun walkFor(episode: DiscoverEpisodeRef?, claim: (suspend () -> InstantStart?)?): DebridPlayback {
        val d = detail()
        lateinit var walk: DebridPlayback
        walk = DebridPlayback(
            api = api, gps = gps, search = { searchFor(episode).await() },
            resolve = { option, resume -> resolve(option, episode, resume) },
            play = { _, session, resume -> play(session, episode, resume) },
            onCaching = { c ->
                caching = Caching(c.providerId, c.torrentId, c.option, episode, walk)
                gps.beginCachingPreparation(
                    title = d.title,
                    onCancel = ::cancelCaching,
                    onChooseRelease = {
                        cancelCaching()
                        pick(episode)
                    },
                )
            },
            want = wantFor(d, episode),
            title = d.title,
            posterUrl = d.posterUrl,
            context = listOfNotNull("Discover ${d.mediaType}: ${d.title}", episode?.label),
            claim = claim,
        )
        return walk
    }

    private fun wantFor(d: DiscoverDetail, episode: DiscoverEpisodeRef?): WantAction? = when {
        !api.canWant -> null
        episode != null -> WantAction { api.wantDiscoverEpisode(d, episode) }
        d.mediaType == "movie" && !isWantedStatus(d.wantStatus) -> WantAction { api.wantMovie(d) }
        else -> null
    }

    private suspend fun resolve(option: StreamOption, episode: DiscoverEpisodeRef?, resumeSec: Int) =
        api.streamResolve(
            option = option,
            mediaType = detail().mediaType,
            startTime = resumeRequestSeconds(resumeSec),
            autoCreate = detail(),
            autoCreateEpisode = episode,
        )

    private suspend fun play(session: KobblerApi.StreamSession, episode: DiscoverEpisodeRef?, resumeSec: Int) {
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
                seekToSec = resumeSeekSeconds(resumeSec, session.serverStartSeconds),
                session = session,
            )
            gps.isDebrid = true
            gps.playUnmuted(p)
            gps.isFullscreen = true
        }
        onViewInLibrary(libraryType, libraryId)
    }

    suspend fun pollCaching() {
        val c = caching ?: return
        val failure = gps.awaitDebridCache(api, c.providerId, c.torrentId, c.option.debridProvider)
        // Clearing [caching] ends the effect running this, so the walk is handed off rather than run here.
        caching = null
        if (failure != null) {
            c.walk.giveUp(failure)
            return
        }
        gps.endCachingPreparation()
        c.walk.resumeCached(c.option) { 0 }
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

private const val FINDING_NOTE = "Finding a stream…"

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
