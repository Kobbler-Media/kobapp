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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import dev.kobbler.kobfocus.focusInert
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.Movie
import dev.kobbler.app.data.StreamOption
import dev.kobbler.app.data.StreamSearchResult
import dev.kobbler.app.data.StreamTarget
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.platform.supportsVideoPlayback
import dev.kobbler.app.ui.detail.MovieDetailScreen
import dev.kobbler.app.ui.detail.StreamOptionsOverlay
import dev.kobbler.app.ui.detail.streamingAvailability
import dev.kobbler.app.ui.navigation.Destination
import dev.kobbler.kobplayer.Player
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch


@Composable
fun InlineMoviePlayer(
    movie: Movie,
    api: KobblerApi,
    formFactor: FormFactor,
    player: Player?,
    gps: GlobalPlayerState,
    autoPlay: Boolean = false,
    onBack: () -> Unit,
    onPersonClick: ((String) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()

    var prefetchedSession by remember { mutableStateOf<KobblerApi.StreamSession?>(null) }
    var prewarmGeneration by remember(movie.id) { mutableIntStateOf(0) }

    // The autoplay intent rides on the nav destination, so it stays set for as long as
    // this screen is on the stack — and the prewarm below is deliberately re-armed the
    // moment the player lets go, so an intent left standing has the prewarm raise the
    // player again over a title nobody asked to watch twice. Spent once, by whichever
    // path acts on it.
    var autoPlayPending by remember(movie.id) { mutableStateOf(autoPlay) }

    // The prewarm dies with the playback it fed — gps.stop() closes its session. Mint the
    // next one as soon as the player lets go, so coming back to this page and pressing
    // Play is as quick as the first time rather than a cold open.
    LaunchedEffect(gps.mediaId) {
        if (gps.mediaId == null && prefetchedSession != null) {
            prefetchedSession = null
            prewarmGeneration++
        }
    }

    // A page can sit open for a long time before the press that uses what it warmed.
    KeepSessionWarm(api, prefetchedSession?.sessionId.takeIf { gps.mediaId == null }) {
        prefetchedSession = null
        prewarmGeneration++
    }

    var streamResult by remember { mutableStateOf<StreamSearchResult?>(null) }
    var streamLoading by remember { mutableStateOf(false) }
    var showStreamOptions by remember { mutableStateOf(false) }
    var cachingProviderId by remember { mutableStateOf<String?>(null) }
    var cachingTorrentId by remember { mutableStateOf<String?>(null) }
    var cachingOption by remember { mutableStateOf<StreamOption?>(null) }
    var cachingWalk by remember { mutableStateOf<DebridPlayback?>(null) }

    var debridFallback by remember { mutableStateOf(false) }
    // Carried to the caching poll, which resolves long after the press that asked for it.
    var debridFromStart by remember { mutableStateOf(false) }
    val isDebrid = debridFallback || (movie.filePath == null && api.canStream)

    val movieSubtitle = "${movie.year} • ${movie.duration ?: ""}"

    WarmStream(
        gps, api,
        target = movie.instantTarget()?.takeIf { player != null && api.canStream && supportsVideoPlayback },
        startTime = movie.resumeSeconds,
    )

    LaunchedEffect(movie.id, prewarmGeneration) {
      try {
        if (movie.filePath != null && supportsVideoPlayback) {
            try {
                PlaybackTrace.begin("prewarm movie ${movie.title}")
                val start = startPlayback(api, "movie", movie.id, movie.resumeSeconds)
                val session = start?.session
                if (start != null && session != null) {
                    prefetchedSession = session
                    // Only when the player is on its way to the screen. Opening the stream
                    // is what picks a decoder, and it can only pick a hardware one against
                    // a surface — which the player owns and does not have until it is up.
                    // A browse therefore prewarms the session and stops there; the tap
                    // that follows raises the player before it opens anything.
                    // mediaId, not isActive: a player shell raised for this navigation is
                    // waiting on exactly this load.
                    if (player != null && gps.mediaId == null && autoPlayPending) {
                        gps.isFullscreen = true
                        gps.load(
                            player = player, api = api,
                            type = "movie", id = movie.id,
                            streamUrl = session.url, newSessionId = session.sessionId,
                            newTitle = movie.title,
                            newSubtitle = movieSubtitle,
                            newPosterUrl = movie.posterUrl, newLogoUrl = movie.logoUrl,
                            destination = Destination.MovieDetail(movie.id),
                            seekToSec = start.seekToSec,
                            session = session,
                        )
                    }
                } else if (api.canStream) {
                    debridFallback = true
                    streamLoading = true
                    runCatching { streamResult = api.streamSearch(movieId = movie.id) }
                    streamLoading = false
                }
            } catch (_: Exception) {
                if (api.canStream) {
                    debridFallback = true
                    streamLoading = true
                    runCatching { streamResult = api.streamSearch(movieId = movie.id) }
                    streamLoading = false
                }
            }
        } else if (movie.filePath == null && api.canStream && supportsVideoPlayback) {
            streamLoading = true
            runCatching { streamResult = api.streamSearch(movieId = movie.id) }
            streamLoading = false
        }
      } finally {
          // Nothing to hand the shell: take it back down rather than leave a spinner.
          if (gps.mediaId == null && streamResult?.options.isNullOrEmpty()) gps.cancelPreparing()
      }
    }

    suspend fun movieStreams(): StreamSearchResult = streamResult
        ?: runCatching { api.streamSearch(movieId = movie.id) }
            .getOrElse { StreamSearchResult(emptyList(), listOf("Search failed")) }
            .also { streamResult = it }

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
        gps.beginCachingPreparation(
            title = movie.title,
            onCancel = cancelCaching,
            onChooseRelease = {
                cancelCaching()
                showStreamOptions = true
            },
        )
    }

    // `autoPlay` rides on the nav destination, so it stays set for as long as this screen
    // is on the stack. Keying the launch on gps.mediaId — which the launch itself writes —
    // both tore the work down half-way and re-fired the whole thing the moment the player
    // was closed, which is what made back out of a paused player bounce straight back in.
    // The intent is acted on once per screen; the waiting happens inside the effect.
    LaunchedEffect(movie.id) {
        if (!autoPlay || player == null) return@LaunchedEffect
        val alreadyLoaded = snapshotFlow {
            val debrid = debridFallback || (movie.filePath == null && api.canStream)
            when {
                gps.mediaId == movie.id -> true
                debrid && streamResult?.options?.isNotEmpty() == true -> false
                else -> null
            }
        }.filterNotNull().first()

        autoPlayPending = false
        if (alreadyLoaded) {
            gps.playUnmuted(player)
            gps.isFullscreen = true
        } else {
            tryPlayWithFallback(
                api = api, player = player, gps = gps, movie = movie,
                search = { movieStreams() }, option = null,
                resumeSec = movieProgress(api, movie),
                onCaching = startCaching,
                claim = { gps.instant.claim(api, StreamTarget.movie(movie.id)) },
            )
        }
    }

    DisposableEffect(movie.id) {
        if (isDebrid) gps.onSwitchStream = { showStreamOptions = true }
        onDispose { if (gps.mediaId == movie.id) gps.onSwitchStream = null }
    }

    LaunchedEffect(cachingProviderId, cachingTorrentId) {
        val pid = cachingProviderId ?: return@LaunchedEffect
        val tid = cachingTorrentId ?: return@LaunchedEffect
        val opt = cachingOption ?: return@LaunchedEffect
        val walk = cachingWalk ?: return@LaunchedEffect
        val failure = gps.awaitDebridCache(api, pid, tid, opt.debridProvider)
        cachingProviderId = null; cachingTorrentId = null; cachingOption = null; cachingWalk = null
        if (failure != null) {
            walk.giveUp(failure)
            return@LaunchedEffect
        }
        gps.endCachingPreparation()
        val fromStart = debridFromStart
        walk.resumeCached(opt) { if (fromStart) 0 else movieProgress(api, movie) }
    }

    DisposableEffect(movie.id) {
        onDispose {
            val weOwnPlayer = gps.mediaId == movie.id
            if (weOwnPlayer && !gps.isFullscreen && !gps.isMiniPlayer) {
                player?.let { gps.stop(it, api, gps.teardownScope) }
            } else if (!weOwnPlayer) {
                prefetchedSession?.sessionId?.let { sid -> gps.teardownScope.launch { api.closeSession(sid) } }
            }
        }
    }

    val autoPlayDebrid: (fromStart: Boolean) -> Unit = { fromStart ->
        if (player != null) {
            debridFromStart = fromStart
            var resolving: Job? = null
            // Same reason the episode path raises one: walking the option list is a
            // resolve round-trip each, and without the shell the tap sits there looking
            // dead until one of them answers.
            gps.beginPreparing(
                id = movie.id,
                newTitle = movie.title,
                newSubtitle = movie.year.takeIf { it > 0 }?.toString(),
                newPosterUrl = movie.posterUrl,
                onCancel = {
                    resolving?.cancel()
                    gps.cancelPreparing()
                },
            )
            resolving = scope.launch {
                tryPlayWithFallback(
                    api = api, player = player, gps = gps, movie = movie,
                    search = { movieStreams() }, option = null,
                    resumeSec = if (fromStart) 0 else movieProgress(api, movie),
                    onCaching = startCaching,
                    claim = { gps.instant.claim(api, StreamTarget.movie(movie.id)) },
                )
                if (gps.mediaId == null && gps.loadingError == null) gps.cancelPreparing()
            }
        }
    }

    // Warming finishes after the search returns, so an option listed as unknown can
    // become verified-instant while the picker is open — fold those in rather than
    // leaving the badge stale. Gated on the picker, not on `streamResult`: that is
    // never cleared once an autoplay search has run, so it would hold the connection
    // open for the life of the detail screen.
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

    val shown = movie.withReportedProgress(gps.reportedPositions)
    Box(Modifier.fillMaxSize()) {
      // Contain the detail screen while the stream overlay is up.
      Box(Modifier.focusInert(showStreamOptions)) {
        MovieDetailScreen(
            movie = shown,
            api = api,
            formFactor = formFactor,
            onBack = onBack,
            onWatchedChanged = { gps.noteWatched(movie.id, it) },
            onPlay = { fromStart ->
                if (player == null) return@MovieDetailScreen
                if (isDebrid && prefetchedSession == null) {
                    autoPlayDebrid(fromStart)
                } else {
                    scope.launch {
                        try {
                            if (gps.mediaId == movie.id && !fromStart) {
                                PlaybackTrace.begin("play movie ${movie.title} (prewarmed)")
                                gps.playUnmuted(player)
                                gps.isFullscreen = true
                            } else {
                                PlaybackTrace.begin("play movie ${movie.title} (cold)")
                                // Raised before the load, not after: the player is what
                                // owns the video surface, and the surface is what decides
                                // whether the decoder can be a hardware one.
                                gps.isFullscreen = true
                                // The player closes its session on the way out, so the one
                                // prewarmed when this screen opened is dead by the second
                                // play — VLC gets a 404 and reports it as a playback error.
                                // Reaching here means the player is not holding this title,
                                // so the prewarm is spent either way; mint a live one.
                                // Restarting a title the player already holds: load() keeps the
                                // session of a same-id reload, so it is ours to hand back.
                                val held = gps.sessionId.takeIf { gps.mediaId == movie.id }
                                setOfNotNull(prefetchedSession?.sessionId, held).forEach { spent ->
                                    gps.teardownScope.launch { api.closeSession(spent) }
                                }
                                val start = startPlayback(api, "movie", movie.id, shown.resumeSeconds, fromStart)
                                    ?: throw IllegalStateException("Server returned no stream URL for movie ${movie.id}")
                                prefetchedSession = start.session
                                gps.load(
                                    player = player, api = api,
                                    type = "movie", id = movie.id,
                                    streamUrl = start.session.url, newSessionId = start.session.sessionId,
                                    newTitle = movie.title,
                                    newSubtitle = movieSubtitle,
                                    newPosterUrl = movie.posterUrl, newLogoUrl = movie.logoUrl,
                                    destination = Destination.MovieDetail(movie.id),
                                    seekToSec = start.seekToSec,
                                    session = start.session,
                                )
                                gps.playUnmuted(player)
                            }
                        } catch (e: Exception) {
                            gps.failWith(e, listOf("Movie: ${movie.title}", "ID: ${movie.id}"))
                        }
                    }
                }
            },
            onPersonClick = onPersonClick,
            keep = rememberKeepAction(api, gps, "movie", if (movie.filePath == null) setOf(movie.id) else emptySet()),
            streaming = streamingAvailability(
                canStream = isDebrid && supportsVideoPlayback,
                searching = streamLoading,
                found = streamResult?.options?.map { it.isInstant },
            ),
            onStream = if (isDebrid && supportsVideoPlayback) autoPlayDebrid else null,
            onStreamPick = if (isDebrid && supportsVideoPlayback) {
                { showStreamOptions = true }
            } else null,
        )
      }

        if (showStreamOptions) {
            StreamOptionsOverlay(
                options = streamResult?.options ?: emptyList(),
                errors = streamResult?.errors ?: emptyList(),
                loading = streamLoading,
                onSelect = { option ->
                    showStreamOptions = false
                    debridFromStart = false
                    scope.launch {
                        tryPlayWithFallback(
                            api = api, player = player, gps = gps, movie = movie,
                            search = { movieStreams() },
                            option = option,
                            resumeSec = movieProgress(api, movie),
                            onCaching = startCaching,
                        )
                    }
                },
                onDismiss = { showStreamOptions = false },
            )
        }

    }
}

private suspend fun tryPlayWithFallback(
    api: KobblerApi,
    player: Player?,
    gps: GlobalPlayerState,
    movie: Movie,
    search: suspend () -> StreamSearchResult,
    option: StreamOption?,
    resumeSec: Int = 0,
    onCaching: (DebridPlayback, WalkResult.Caching) -> Unit,
    claim: (suspend () -> InstantStart?)? = null,
) {
    if (player == null) return
    lateinit var walk: DebridPlayback
    walk = DebridPlayback(
        api = api, gps = gps, search = search,
        resolve = { opt, resume ->
            api.streamResolve(option = opt, mediaType = "movie", mediaId = movie.id, startTime = resumeRequestSeconds(resume))
        },
        play = { _, session, resume -> playDebridSession(gps, player, api, session, movie, resume) },
        onCaching = { onCaching(walk, it) },
        want = movie.takeIf { it.canBeWanted(api.canWant) }?.let { WantAction { api.wantMovieById(it.id) } },
        title = movie.title,
        posterUrl = movie.posterUrl,
        context = listOf("Movie: ${movie.title}", "ID: ${movie.id}"),
        claim = claim,
    )
    walk.start(option, resumeSec)
}

private suspend fun playDebridSession(
    gps: GlobalPlayerState,
    player: Player,
    api: KobblerApi,
    session: KobblerApi.StreamSession,
    movie: Movie,
    resumeSec: Int,
) {
    gps.load(
        player = player, api = api,
        type = "movie", id = movie.id,
        streamUrl = session.url, newSessionId = session.sessionId,
        newTitle = movie.title,
        newSubtitle = "${movie.year} • ${movie.duration ?: ""}",
        newPosterUrl = movie.posterUrl, newLogoUrl = movie.logoUrl,
        destination = Destination.MovieDetail(movie.id),
        seekToSec = resumeSeekSeconds(resumeSec, session.serverStartSeconds),
        session = session,
    )
    gps.isDebrid = true
    gps.playUnmuted(player)
    gps.isFullscreen = true
}

/**
 * The freshest saved position for [movie]. The detail payload carries one too, but it
 * comes from a cached fetch and goes stale the moment the title is watched from
 * anywhere else.
 */
private suspend fun movieProgress(api: KobblerApi, movie: Movie): Int =
    api.getMovieProgress(movie.id) ?: movie.resumeSeconds ?: 0
