package dev.kobbler.app.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.kobbler.kobfocus.focusInert
import dev.kobbler.app.data.Episode
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.RecentStreamDto
import dev.kobbler.app.data.StreamOption
import dev.kobbler.app.data.StreamSearchResult
import dev.kobbler.app.data.TvShow
import dev.kobbler.app.data.indexOfEpisode
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.platform.supportsVideoPlayback
import dev.kobbler.app.ui.detail.StreamOptionsOverlay
import dev.kobbler.app.ui.detail.TvShowDetailScreen
import dev.kobbler.app.ui.navigation.Destination
import dev.kobbler.kobplayer.Player
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


@Composable
fun InlineTvPlayer(
    show: TvShow,
    api: KobblerApi,
    formFactor: FormFactor,
    player: Player?,
    gps: GlobalPlayerState,
    autoPlay: Boolean = false,
    onBack: () -> Unit,
    onPersonClick: ((String) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var prefetchedSessionId by remember { mutableStateOf<String?>(null) }
    var prefetchedEpisodeId by remember { mutableStateOf<String?>(null) }

    // The prewarmed session is a real one on the server, held for a press that may be a
    // long time coming. Asking is the touch that keeps it, and how we learn it has gone.
    KeepSessionWarm(api, prefetchedSessionId.takeIf { gps.mediaId == null }) {
        prefetchedSessionId = null
        prefetchedEpisodeId = null
    }

    var streamResult by remember { mutableStateOf<StreamSearchResult?>(null) }
    // Which episode streamResult was searched for. Without it a second episode reused
    // the first one's sources: the old guard compared streamingEpisodeId, which the
    // caller had already set to the episode it was about to search for.
    var streamSearchedEpisodeId by remember { mutableStateOf<String?>(null) }
    var streamLoading by remember { mutableStateOf(false) }
    var showStreamOptions by remember { mutableStateOf(false) }
    var cachingProviderId by remember { mutableStateOf<String?>(null) }
    var cachingTorrentId by remember { mutableStateOf<String?>(null) }
    var cachingOption by remember { mutableStateOf<StreamOption?>(null) }
    var cachingWalk by remember { mutableStateOf<DebridPlayback?>(null) }
    var streamingEpisodeId by remember { mutableStateOf<String?>(null) }
    var streamingSeasonIdx by remember { mutableIntStateOf(0) }
    var streamingEpisodeIdx by remember { mutableIntStateOf(0) }
    // Carried to the caching poll, which resolves long after the press that asked for it.
    var streamFromStart by remember { mutableStateOf(false) }

    val cancelCaching: () -> Unit = {
        cachingProviderId = null
        cachingTorrentId = null
        cachingOption = null
        cachingWalk = null
        gps.endCachingPreparation()
        // preparingFor too: left set it keeps gps.isActive true, and the next
        // beginPreparing silently no-ops — no loading shell on the following stream.
        gps.cancelPreparing()
        gps.isPreparing = false
        gps.isFullscreen = false
    }
    val startCaching: (DebridPlayback, WalkResult.Caching) -> Unit = { walk, c ->
        cachingProviderId = c.providerId
        cachingTorrentId = c.torrentId
        cachingOption = c.option
        cachingWalk = walk
        gps.beginCachingPreparation(title = show.title, onCancel = cancelCaching)
    }

    LaunchedEffect(show.id) {
        if (player == null || !supportsVideoPlayback) {
            gps.cancelPreparing()
            return@LaunchedEffect
        }
        // mediaId, not isActive: a player shell raised for this navigation is waiting on
        // exactly the load below, and must not be mistaken for one already playing.
        if (gps.mediaId != null) return@LaunchedEffect

        // The position on `show` is from a cached payload — wherever this device last saw
        // the episode — so it only seeds the rebase; the server says where to resume.
        val (episodeId, cachedSec) = resolveStartEpisode(show, api)
            ?: run {
                gps.cancelPreparing()
                return@LaunchedEffect
            }

        if (autoPlay) PlaybackTrace.begin("play episode $episodeId (autoplay)")
        try {
            val start = startPlayback(api, "episode", episodeId, cachedSec)
            val session = start?.session
            if (start != null && session != null) {
                prefetchedEpisodeId = episodeId
                prefetchedSessionId = session.sessionId
                val (seasonObj, episode) = findEpisode(show, episodeId)
                // Only when the player is on its way to the screen — see the same guard in
                // InlineMoviePlayer. Opening the stream picks the decoder, and it needs a
                // surface to pick a hardware one.
                if (!autoPlay) return@LaunchedEffect
                gps.isFullscreen = true
                gps.loadEpisode(
                    player = player, api = api, show = show,
                    episodeId = episodeId, season = seasonObj, episode = episode,
                    streamUrl = session.url, sessionId = session.sessionId,
                    seekToSec = start.seekToSec,
                    session = session,
                )
                gps.playUnmuted(player)
            } else if (api.canStream && autoPlay) {
                val match = show.seasons.indexOfEpisode(episodeId)
                if (match != null) {
                    streamingEpisodeId = episodeId
                    streamingSeasonIdx = match.first
                    streamingEpisodeIdx = match.second
                }
                streamLoading = true
                streamResult = runCatching { api.streamSearch(episodeId = episodeId) }.getOrNull()
                streamSearchedEpisodeId = episodeId
                streamLoading = false
                val options = streamResult?.options.orEmpty()
                tryPlayTvWithFallback(
                    api = api, player = player, gps = gps, show = show,
                    episodeId = episodeId, seasonIdx = streamingSeasonIdx, episodeIdx = streamingEpisodeIdx,
                    options = options, option = options.firstOrNull(),
                    resumeSec = api.getEpisodeProgress(episodeId) ?: cachedSec,
                    searchErrors = streamResult?.errors.orEmpty(),
                    onCaching = startCaching,
                )
            }
        } catch (_: Exception) {
        } finally {
            // Nothing to hand the shell: take it back down rather than leave a spinner.
            if (gps.mediaId == null) gps.cancelPreparing()
        }
    }

    DisposableEffect(show.id) {
        gps.onSwitchStream = { showStreamOptions = true }
        onDispose {
            if (gps.mediaId == prefetchedEpisodeId || gps.mediaId == streamingEpisodeId) {
                gps.onSwitchStream = null
            }
        }
    }

    LaunchedEffect(cachingProviderId, cachingTorrentId) {
        val pid = cachingProviderId ?: return@LaunchedEffect
        val tid = cachingTorrentId ?: return@LaunchedEffect
        val opt = cachingOption ?: return@LaunchedEffect
        val walk = cachingWalk ?: return@LaunchedEffect
        val epId = streamingEpisodeId ?: return@LaunchedEffect
        val clear = { cachingProviderId = null; cachingTorrentId = null; cachingOption = null; cachingWalk = null }
        while (true) {
            delay(3000)
            try {
                val status = api.streamResolveStatus(pid, tid)
                when (status.status) {
                    "ready" -> {
                        clear()
                        gps.endCachingPreparation()
                        walk.start(opt, if (streamFromStart) 0 else (api.getEpisodeProgress(epId) ?: 0))
                        break
                    }
                    "downloading" -> gps.setCachingProgress(status.progress?.toFloat() ?: 0f)
                    "error" -> {
                        clear()
                        walk.giveUp(IllegalStateException(status.message ?: "Stream failed"))
                        break
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                clear()
                walk.giveUp(e)
                break
            }
        }
    }

    DisposableEffect(show.id) {
        onDispose {
            val weOwnPlayer = gps.mediaId == prefetchedEpisodeId
            if (weOwnPlayer && !gps.isFullscreen && !gps.isMiniPlayer) {
                player?.let { gps.stop(it, api, gps.teardownScope) }
            } else if (!weOwnPlayer) {
                prefetchedSessionId?.let { sid -> gps.teardownScope.launch { api.closeSession(sid) } }
            }
        }
    }

    DisposableEffect(show.id) {
        gps.onFinished = onEpisodeFinishedHandler(
            scope = scope, api = api, player = player, gps = gps, show = show,
            onStartCaching = { walk, c, sIdx, eIdx, epId ->
                cachingProviderId = c.providerId; cachingTorrentId = c.torrentId; cachingOption = c.option
                cachingWalk = walk
                streamingEpisodeId = epId; streamingSeasonIdx = sIdx; streamingEpisodeIdx = eIdx
                gps.beginCachingPreparation(title = show.title, onCancel = cancelCaching)
            },
        )
        onDispose { gps.onFinished = null }
    }

    // Recomputed as playback moves so the controls can hide a step that has nowhere to
    // go — the first and last episode of a show each lose one.
    DisposableEffect(gps.mediaId, show) {
        val startCachingAt = { walk: DebridPlayback, c: WalkResult.Caching, sIdx: Int, eIdx: Int, epId: String ->
            cachingProviderId = c.providerId; cachingTorrentId = c.torrentId; cachingOption = c.option
            cachingWalk = walk
            streamingEpisodeId = epId; streamingSeasonIdx = sIdx; streamingEpisodeIdx = eIdx
            gps.beginCachingPreparation(title = show.title, onCancel = cancelCaching)
        }
        gps.onSkipNext = neighbourEpisode(show, gps.mediaId, 1)?.let { target ->
            { playNeighbourEpisode(scope, api, player, gps, show, target, startCachingAt) }
        }
        gps.onSkipPrevious = neighbourEpisode(show, gps.mediaId, -1)?.let { target ->
            { playNeighbourEpisode(scope, api, player, gps, show, target, startCachingAt) }
        }
        // Cross-device handoff: another device is further into this show. The switch
        // resumes from the server's saved position, which is that device's.
        gps.onPlayEpisode = { epId ->
            episodeById(show, epId)?.let { target ->
                playNeighbourEpisode(scope, api, player, gps, show, target, startCachingAt)
            }
        }
        onDispose {
            gps.onSkipNext = null
            gps.onSkipPrevious = null
            gps.onPlayEpisode = null
        }
    }

    fun autoPlayDebridEpisode(sIdx: Int, eIdx: Int, fromStart: Boolean = false) {
        val season = show.seasons.getOrNull(sIdx) ?: return
        val episode = season.episodes.getOrNull(eIdx) ?: return
        val episodeId = episode.id ?: return
        if (player == null) return
        streamFromStart = fromStart
        streamingEpisodeId = episodeId
        streamingSeasonIdx = sIdx
        streamingEpisodeIdx = eIdx

        var search: Job? = null
        // The search is seconds of provider round-trips and it is the whole wait on this
        // path — streamLoading is only ever drawn by the options overlay, which is not
        // open here, so the tap used to sit there looking dead.
        gps.beginPreparing(
            id = episodeId,
            newTitle = show.title,
            newSubtitle = "S${season.number} E${episode.number} • ${episode.title}",
            newPosterUrl = show.posterUrl,
            note = "Not in your library — searching for a stream.",
            onCancel = {
                search?.cancel()
                streamLoading = false
                gps.cancelPreparing()
            },
        )
        search = scope.launch {
            if (streamResult == null || streamSearchedEpisodeId != episodeId) {
                streamLoading = true
                streamResult = runCatching { api.streamSearch(episodeId = episodeId) }.getOrNull()
                streamSearchedEpisodeId = episodeId
                streamLoading = false
            }
            val options = streamResult?.options.orEmpty()
            tryPlayTvWithFallback(
                api = api, player = player, gps = gps, show = show,
                episodeId = episodeId, seasonIdx = sIdx, episodeIdx = eIdx,
                options = options, option = options.firstOrNull(),
                resumeSec = if (fromStart) 0 else (api.getEpisodeProgress(episodeId) ?: 0),
                searchErrors = streamResult?.errors.orEmpty(),
                onCaching = startCaching,
            )
            if (gps.mediaId == null && gps.loadingError == null) gps.cancelPreparing()
        }
    }

    // Warming finishes after the search returns, so an option listed as unknown can
    // become verified-instant while the picker is open. Fold those in rather than
    // leaving the badge stale for the life of the result.
    // Only while the picker is open. This holds a connection, and `streamResult` is
    // never cleared once an autoplay search has run — gating on it would keep the
    // socket up for the life of the detail screen.
    LaunchedEffect(showStreamOptions) {
        if (!showStreamOptions) return@LaunchedEffect
        api.libraryEvents().collect { frame ->
            if (frame.kind != "stream_warm_ready") return@collect
            val hash = frame.data["info_hash"]?.jsonPrimitive?.contentOrNull ?: return@collect
            val providerId = frame.data["debrid_provider_id"]?.jsonPrimitive?.contentOrNull ?: return@collect
            val fid = frame.data["file_id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            streamResult = applyWarmReady(streamResult, hash, fid, providerId)
        }
    }

    Box(Modifier.fillMaxSize()) {
      // The overlay is painted over this, not instead of it — contain it or D-pad
      // walks out of the overlay and back into the detail screen behind.
      Box(Modifier.focusInert(showStreamOptions)) {
        TvShowDetailScreen(
            show = show.withReportedProgress(gps.reportedPositions),
            formFactor = formFactor,
            onBack = onBack,
            api = api,
            onPlayEpisode = { sIdx, eIdx, fromStart ->
                if (player == null) return@TvShowDetailScreen
                val episode = show.seasons.getOrNull(sIdx)?.episodes?.getOrNull(eIdx) ?: return@TvShowDetailScreen
                val episodeId = episode.id ?: return@TvShowDetailScreen
                val season = show.seasons[sIdx]

                scope.launch {
                    try {
                        if (gps.mediaId == episodeId && !fromStart) {
                            PlaybackTrace.begin("play episode $episodeId (prewarmed)")
                            gps.playUnmuted(player)
                            gps.isFullscreen = true
                        } else {
                            PlaybackTrace.begin("play episode $episodeId (cold)")
                            gps.isFullscreen = true
                            val oldSid = if (gps.mediaId != null) gps.sessionId else prefetchedSessionId
                            if (oldSid != null) gps.teardownScope.launch { api.closeSession(oldSid) }

                            val start = startPlayback(api, "episode", episodeId, fromStart = fromStart)
                            if (start != null) {
                                gps.loadEpisode(
                                    player = player, api = api, show = show,
                                    episodeId = episodeId, season = season, episode = episode,
                                    streamUrl = start.session.url, sessionId = start.session.sessionId,
                                    seekToSec = start.seekToSec,
                                    session = start.session,
                                )
                                gps.playUnmuted(player)
                            } else if (api.canStream) {
                                autoPlayDebridEpisode(sIdx, eIdx, fromStart)
                            } else {
                                throw IllegalStateException("No stream available for episode $episodeId (no local file, debrid disabled)")
                            }
                        }
                    } catch (e: Exception) {
                        gps.failWith(e, listOf(
                            "Show: ${show.title}",
                            "Episode: S${season.number}E${episode.number} ${episode.title}",
                            "ID: $episodeId",
                        ))
                    }
                }
            },
            onPersonClick = onPersonClick,
            onStreamEpisode = if (api.canStream && supportsVideoPlayback) { sIdx, eIdx ->
                autoPlayDebridEpisode(sIdx, eIdx)
            } else null,
            onStreamEpisodePick = if (api.canStream && supportsVideoPlayback) { sIdx, eIdx ->
                val episode = show.seasons.getOrNull(sIdx)?.episodes?.getOrNull(eIdx) ?: return@TvShowDetailScreen
                val episodeId = episode.id ?: return@TvShowDetailScreen
                streamingEpisodeId = episodeId
                streamingSeasonIdx = sIdx
                streamingEpisodeIdx = eIdx
                showStreamOptions = true
                streamLoading = true
                scope.launch {
                    streamResult = runCatching { api.streamSearch(episodeId = episodeId) }
                        .getOrElse { StreamSearchResult(emptyList(), listOf("Search failed")) }
                    streamSearchedEpisodeId = episodeId
                    streamLoading = false
                }
            } else null,
        )
      }

        if (showStreamOptions) {
            var recentStreams by remember { mutableStateOf<List<RecentStreamDto>>(emptyList()) }
            LaunchedEffect(Unit) {
                runCatching { recentStreams = api.getRecentStreams() }
            }
            StreamOptionsOverlay(
                options = streamResult?.options ?: emptyList(),
                errors = streamResult?.errors ?: emptyList(),
                loading = streamLoading,
                onSelect = { option ->
                    showStreamOptions = false
                    streamFromStart = false
                    val epId = streamingEpisodeId ?: return@StreamOptionsOverlay
                    scope.launch {
                        tryPlayTvWithFallback(
                            api = api, player = player, gps = gps, show = show,
                            episodeId = epId, seasonIdx = streamingSeasonIdx, episodeIdx = streamingEpisodeIdx,
                            options = streamResult?.options ?: emptyList(),
                            option = option,
                            resumeSec = (api.getEpisodeProgress(epId) ?: 0),
                            onCaching = startCaching,
                        )
                    }
                },
                onDismiss = { showStreamOptions = false },
                api = api,
                recentStreams = recentStreams,
            )
        }

    }
}

private suspend fun resolveStartEpisode(show: TvShow, api: KobblerApi): Pair<String, Int>? {
    val cont = show.continueEpisode
    if (cont != null) return cont.episodeId to cont.positionSeconds

    val (_, _, ep) = findLikelyNextEpisode(show, api) ?: return null
    val epId = ep.id ?: return null
    val pos = (api.getEpisodeProgress(epId) ?: 0)
    return epId to pos
}

private fun findEpisode(show: TvShow, episodeId: String): Pair<dev.kobbler.app.data.Season?, Episode?> {
    val seasonObj = show.seasons.find { s -> s.episodes.any { it.id == episodeId } }
    val episode = seasonObj?.episodes?.find { it.id == episodeId }
    return seasonObj to episode
}

internal suspend fun GlobalPlayerState.loadEpisode(
    player: Player,
    api: KobblerApi,
    show: TvShow,
    episodeId: String,
    season: dev.kobbler.app.data.Season?,
    episode: Episode?,
    streamUrl: String,
    sessionId: String?,
    seekToSec: Int = 0,
    session: KobblerApi.StreamSession? = null,
    isDebrid: Boolean = false,
) {
    val subtitle = season?.let { s -> episode?.let { e -> "S${s.number} E${e.number} • ${e.title}" } }
    load(
        player = player, api = api,
        type = "episode", id = episodeId,
        streamUrl = streamUrl, newSessionId = sessionId,
        newTitle = show.title,
        newSubtitle = subtitle,
        newPosterUrl = show.posterUrl, newLogoUrl = show.logoUrl,
        destination = Destination.TvShowDetail(show.id),
        seekToSec = seekToSec,
        session = session,
    )
    // Travels with the load: a local load after a debrid one must clear the flag,
    // or auto-advance and session recovery misread the source of the playback.
    this.isDebrid = isDebrid
}

/**
 * The episode [delta] steps from [currentEpId] in broadcast order, across season
 * boundaries. Null at either end of the show.
 */
private fun neighbourEpisode(show: TvShow, currentEpId: String?, delta: Int): Triple<Int, Int, Episode>? {
    if (currentEpId == null) return null
    val allEps = show.seasons.flatMapIndexed { sIdx, season ->
        season.episodes.mapIndexed { eIdx, ep -> Triple(sIdx, eIdx, ep) }
    }
    val currentIdx = allEps.indexOfFirst { it.third.id == currentEpId }
    if (currentIdx < 0) return null
    return allEps.getOrNull(currentIdx + delta)?.takeIf { it.third.id != null }
}

private fun episodeById(show: TvShow, episodeId: String): Triple<Int, Int, Episode>? {
    show.seasons.forEachIndexed { sIdx, season ->
        season.episodes.forEachIndexed { eIdx, ep ->
            if (ep.id == episodeId) return Triple(sIdx, eIdx, ep)
        }
    }
    return null
}

private fun playNeighbourEpisode(
    scope: CoroutineScope,
    api: KobblerApi,
    player: Player?,
    gps: GlobalPlayerState,
    show: TvShow,
    target: Triple<Int, Int, Episode>,
    onStartCaching: (walk: DebridPlayback, caching: WalkResult.Caching, sIdx: Int, eIdx: Int, episodeId: String) -> Unit,
    allowDebridFallback: Boolean = true,
) {
    val (sIdx, eIdx, ep) = target
    val epId = ep.id ?: return
    val season = show.seasons[sIdx]
    scope.launch {
        try {
            val oldSid = gps.sessionId
            if (oldSid != null) gps.teardownScope.launch { api.closeSession(oldSid) }
            val start = startPlayback(api, "episode", epId)
            if (start != null && player != null) {
                gps.loadEpisode(
                    player = player, api = api, show = show,
                    episodeId = epId, season = season, episode = ep,
                    streamUrl = start.session.url, sessionId = start.session.sessionId,
                    seekToSec = start.seekToSec,
                    session = start.session,
                )
                gps.playUnmuted(player)
            } else if (allowDebridFallback && api.canStream && player != null) {
                val searchResult = runCatching { api.streamSearch(episodeId = epId) }.getOrNull()
                val options = searchResult?.options
                if (!options.isNullOrEmpty()) {
                    tryPlayTvWithFallback(
                        api = api, player = player, gps = gps, show = show,
                        episodeId = epId, seasonIdx = sIdx, episodeIdx = eIdx,
                        options = options, option = options.first(),
                        resumeSec = (api.getEpisodeProgress(epId) ?: 0),
                        onCaching = { walk, c -> onStartCaching(walk, c, sIdx, eIdx, epId) },
                    )
                } else {
                    // Nothing on debrid for this episode (not aired yet, no release
                    // found). Clean stop rather than a dead player surface.
                    gps.stop(player, api, scope)
                }
            } else if (player != null) {
                // Not in the library, and this playback is not a debrid stream, so
                // no debrid hunt: stopping is the same outcome as the end of a show.
                // Starting it manually still searches.
                gps.stop(player, api, scope)
            }
        } catch (e: Exception) {
            gps.failWith(e, listOf(
                "Switching episode failed",
                "Show: ${show.title}",
                "Episode: S${season.number}E${ep.number} ${ep.title}",
                "ID: $epId",
            ))
        }
    }
}

private fun onEpisodeFinishedHandler(
    scope: CoroutineScope,
    api: KobblerApi,
    player: Player?,
    gps: GlobalPlayerState,
    show: TvShow,
    onStartCaching: (walk: DebridPlayback, caching: WalkResult.Caching, sIdx: Int, eIdx: Int, episodeId: String) -> Unit,
): () -> Unit = {
    val next = neighbourEpisode(show, gps.mediaId, 1)
    if (next != null) {
        // Auto-advance follows the current source: stored playback only advances
        // to the next stored file; debrid playback keeps streaming. Explicit skips
        // and manual starts always keep the debrid fallback.
        playNeighbourEpisode(scope, api, player, gps, show, next, onStartCaching, allowDebridFallback = gps.isDebrid)
    } else if (player != null && gps.mediaId != null) {
        gps.stop(player, api, scope)
    }
}

internal suspend fun tryPlayTvWithFallback(
    api: KobblerApi,
    player: Player?,
    gps: GlobalPlayerState,
    show: TvShow,
    episodeId: String,
    seasonIdx: Int,
    episodeIdx: Int,
    options: List<StreamOption>,
    option: StreamOption?,
    resumeSec: Int = 0,
    searchErrors: List<String> = emptyList(),
    onCaching: (DebridPlayback, WalkResult.Caching) -> Unit,
) {
    if (player == null) return
    val season = show.seasons.getOrNull(seasonIdx)
    val episode = season?.episodes?.getOrNull(episodeIdx)
    lateinit var walk: DebridPlayback
    walk = DebridPlayback(
        api = api, gps = gps, options = options,
        resolve = { opt, resume ->
            api.streamResolve(option = opt, mediaType = "episode", mediaId = episodeId, startTime = resumeRequestSeconds(resume))
        },
        play = { _, session, resume ->
            gps.loadEpisode(
                player = player, api = api, show = show,
                episodeId = episodeId, season = season, episode = episode,
                streamUrl = session.url, sessionId = session.sessionId,
                seekToSec = resumeSeekSeconds(resume, session.serverStartSeconds),
                session = session,
                isDebrid = true,
            )
            gps.playUnmuted(player)
            gps.isFullscreen = true
        },
        onCaching = { onCaching(walk, it) },
        want = episode?.takeIf { it.canBeWanted(api.canWant) }?.let { WantAction { api.wantEpisode(episodeId) } },
        title = show.title,
        posterUrl = show.posterUrl,
        context = listOf(
            "Show: ${show.title}",
            "Episode: S${season?.number ?: "?"}E${episode?.number ?: "?"} ${episode?.title ?: ""}",
            "ID: $episodeId",
        ) + searchErrors,
    )
    walk.start(option, resumeSec)
}

internal suspend fun findLikelyNextEpisode(
    show: TvShow,
    api: KobblerApi,
): Triple<Int, Int, Episode>? {
    val candidates = show.seasons.flatMapIndexed { sIdx, season ->
        season.episodes.mapIndexedNotNull { eIdx, ep ->
            if (ep.filePath != null && ep.id != null) Triple(sIdx, eIdx, ep) else null
        }
    }
    if (candidates.isEmpty()) return null

    for (candidate in candidates.takeLast(3).reversed()) {
        val progressSec = api.getEpisodeProgress(candidate.third.id!!)
        if (progressSec != null && progressSec > 5) return candidate
    }

    return candidates.first()
}
