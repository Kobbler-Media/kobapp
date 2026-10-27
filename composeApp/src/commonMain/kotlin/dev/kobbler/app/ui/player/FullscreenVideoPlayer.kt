package dev.kobbler.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FastForward
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.LibraryAddCheck
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.kobbler.kobfocus.focusInert
import dev.kobbler.app.data.shouldAutoSkipAd
import dev.kobbler.app.data.LiveAdPostureButton
import dev.kobbler.app.ui.safeContentPadding
import dev.kobbler.app.ui.navigation.InterceptBack
import dev.kobbler.kobfocus.LocalFocusScope
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup
import dev.kobbler.app.data.AudioTrackDto
import dev.kobbler.app.data.KeepState
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.ThumbnailsProbe
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.platform.PlaybackLifecycle
import dev.kobbler.app.platform.PlaybackTimeline
import dev.kobbler.app.platform.Settings
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.theme.Section
import dev.kobbler.app.data.SkipSegment
import dev.kobbler.app.theme.accentFor
import dev.kobbler.app.ui.components.KobButton
import dev.kobbler.app.ui.components.KobButtonStyle
import dev.kobbler.app.ui.components.tvAutoGlow
import dev.kobbler.kobplayer.Player
import dev.kobbler.kobplayer.PlayerState
import dev.kobbler.kobplayer.TrackInfo
import dev.kobbler.kobplayer.VideoSurface
import dev.kobbler.kobplayer.mapState
import dev.kobbler.kobplayer.ui.OverlayChapter
import dev.kobbler.kobplayer.ui.SeekBarRegion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val BRIGHTNESS_KEY = "player.brightness"
private const val BRIGHTNESS_PERSIST_SETTLE_MS = 500L
private const val GESTURE_HINT_KEY = "player.gesture_hint_count"
private const val ASPECT_BUCKET_KEY_PREFIX = "aspect_ratio:"

private val SEGMENT_COLORS = mapOf(
    "intro" to Color(0xFF9FE5B0),
    "credits" to Color(0xFFA8C8F0),
    "recap" to Color(0xFFF0E19A),
    "post_credits_scene" to Color(0xFFF0B0C8),
    "ad_break" to Color(0xFFEF4444),
)

private val SEGMENT_LABELS = mapOf(
    "intro" to "Intro",
    "credits" to "Credits",
    "recap" to "Recap",
    "post_credits_scene" to "Post-Credits Scene",
    "ad_break" to "Ad Break",
)

// A detected end lands a little short of EOF, and a stream's duration is not the file's to
// the millisecond.
private const val SKIP_END_SLACK_MS = 5_000L
private const val VIDEO_SKIP_MS = 10_000L

private fun skipRunsToEnd(endMs: Long, durationMs: Long): Boolean =
    durationMs > 0 && endMs >= durationMs - SKIP_END_SLACK_MS

private fun isVisibleSkipSegment(seg: SkipSegment): Boolean =
    seg.source != "chapter" && SEGMENT_LABELS.containsKey(seg.segmentType)

private fun segmentTitle(seg: SkipSegment): String {
    val base = SEGMENT_LABELS[seg.segmentType] ?: seg.segmentType
    val tag = if (seg.source == "manual") "Manual" else "Detected"
    val c = seg.confidence
    val conf = if (c != null && seg.source != "manual") {
        " · ${((c * 100).toInt() / 100.0)}"
    } else ""
    return "$base ($tag$conf)"
}

@Composable
fun FullscreenVideoPlayer(
    player: Player,
    state: GlobalPlayerState,
    api: KobblerApi,
    formFactor: FormFactor,
    onBack: () -> Unit,
    onFinished: (() -> Unit)? = null,
    onToggleFullscreen: (() -> Unit)? = null,
    isFullscreen: Boolean = false,
    videoSurface: (@Composable (Modifier) -> Unit)? = null,
    section: Section = Section.Movies,
    onOpenGuide: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val mediaId = state.mediaId
    val mediaType = state.mediaType
    // Raised by beginPreparing before there is a session: the stream is still being found.
    if (mediaId == null || mediaType == null) {
        if (state.isPreparing && state.isStreamStart) PendingStart(state, KobappTheme.colors.accentFor(section))
        return
    }

    // One rule for every press that moves the playhead, so no control can find the end of
    // a live playlist on its own. See liveSafeSeekMs.
    val seekPlayerTo: (Long) -> Unit = { playerMs ->
        player.seekTo(
            if (mediaType == "livetv") liveSafeSeekMs(playerMs, player.durationMs.value) else playerMs,
        )
    }

    DisposableEffect(Unit) {
        PlaybackLifecycle.setFullscreenMode(true)
        onDispose {
            PlaybackLifecycle.setBrightnessLock(-1f)
            PlaybackLifecycle.setFullscreenMode(false)
            PlaybackLifecycle.setKeepScreenOn(false)
            PlaybackLifecycle.setVideoPlaying(false)
        }
    }

    // Auto-PiP follows playback rather than the player being on screen. Tied to
    // composition, leaving the app with a paused title shrank it into a PiP window that
    // was not playing anything — there is nothing for a picture-in-picture to show, and
    // no way back to the full player except through it.
    LaunchedEffect(player) {
        player.isPlaying.collect { PlaybackLifecycle.setVideoPlaying(it) }
    }
    LaunchedEffect(player) {
        holdScreenWhilePlaying(player.isPlaying, setKeepScreenOn = PlaybackLifecycle::setKeepScreenOn)
    }

    var playerBrightness by remember {
        mutableFloatStateOf(Settings.getString(BRIGHTNESS_KEY)?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f)
    }
    LaunchedEffect(playerBrightness) {
        PlaybackLifecycle.setBrightnessLock(playerBrightness)
        delay(BRIGHTNESS_PERSIST_SETTLE_MS)
        Settings.putString(BRIGHTNESS_KEY, playerBrightness.toString())
    }

    val gestureHints = remember { Settings.getString(GESTURE_HINT_KEY)?.toIntOrNull() ?: 0 }
    var hintActive by remember { mutableStateOf(gestureHints < 3) }
    LaunchedEffect(Unit) {
        if (hintActive) {
            Settings.putString(GESTURE_HINT_KEY, (gestureHints + 1).toString())
            delay(1700)
            hintActive = false
        }
    }

    val playerVolume by player.volume.collectAsState()

    // The controls are left wired on dispose: playback carries on in the mini player, and
    // the notification is the only transport it has. So every callback that launches has
    // to do it somewhere that outlives this composable — a shrink to the mini player
    // cancelled `scope`, and a notification Stop then cleared the UI and left the
    // session running on the server.
    DisposableEffect(player, mediaId) {
        val controlScope = state.teardownScope
        PlaybackLifecycle.setPlayer(player)
        PlaybackLifecycle.setControls(
            onPlayPause = { state.togglePlayback(player, api, controlScope) },
            onSkipForward = { seekPlayerTo((player.currentMs.value + VIDEO_SKIP_MS).coerceAtMost(player.durationMs.value)) },
            onSkipBack = { seekPlayerTo((player.currentMs.value - VIDEO_SKIP_MS).coerceAtLeast(0)) },
            onStop = {
                val pos = state.timeline.toAbsoluteMs(player.currentMs.value)
                if (pos > 5000) {
                    controlScope.launch { state.reportProgress(api, mediaType, mediaId, pos) }
                }
                state.stop(player, api, controlScope)
            },
            timeline = PlaybackTimeline(
                positionMs = { state.timeline.toAbsoluteMs(player.currentMs.value) },
                durationMs = { state.timeline.absoluteDurationMs(player.durationMs.value) },
                seekTo = { ms -> seekPlayerTo(state.timeline.toPlayerMs(ms)) },
                skipBackMs = VIDEO_SKIP_MS,
                skipForwardMs = VIDEO_SKIP_MS,
            ),
        )
        onDispose { }
    }

    LaunchedEffect(player, mediaId) {
        player.isPlaying.collectLatest { playing ->
            while (true) {
                val (vw, vh) = player.videoSize.value
                PlaybackLifecycle.updateNowPlaying(
                    title = state.title,
                    subtitle = state.subtitle ?: "",
                    coverUrl = state.posterUrl ?: "",
                    isPlaying = playing,
                    currentMs = state.timeline.toAbsoluteMs(player.currentMs.value),
                    durationMs = state.timeline.absoluteDurationMs(player.durationMs.value),
                    isAudioOnly = false,
                    videoWidth = vw,
                    videoHeight = vh,
                )
                // Paused position doesn't move: push once, then idle until playback resumes.
                if (!playing) return@collectLatest
                delay(1000)
            }
        }
    }

    val finishTitle: suspend () -> Unit = {
        state.reportProgress(
            api, mediaType, mediaId,
            state.timeline.absoluteDurationMs(player.durationMs.value),
            finished = true,
        )
        // Only a show has somewhere to go next. A film had nowhere, and so went nowhere:
        // the player stayed up over a stream that had stopped, on a frozen last frame.
        if (onFinished != null) onFinished() else onBack()
    }
    val playerState by player.state.collectAsState()
    LaunchedEffect(playerState) {
        if (playerState == dev.kobbler.kobplayer.PlayerState.FINISHED) finishTitle()
    }

    val isInPip by PlaybackLifecycle.isInPipModeFlow.collectAsState()

    var thumbnailCues by remember { mutableStateOf<List<ThumbnailCue>>(emptyList()) }
    LaunchedEffect(state.thumbnailsUrl, state.isLive) {
        val url = state.thumbnailsUrl
        if (url == null) {
            thumbnailCues = emptyList()
            return@LaunchedEffect
        }
        val authToken = api.authHeaders["Authorization"]?.removePrefix("Bearer ") ?: ""
        val baseUrl = thumbnailVttBaseUrl(url)
        val poll = ThumbnailPoll(live = state.isLive)
        var lastVtt: String? = null
        while (true) {
            val fetch = when (val resp = fetchThumbnailVtt(url, api.httpClient, authToken)) {
                VttResponse.Gone -> ThumbnailFetch.Gone
                VttResponse.Failed -> ThumbnailFetch.Failed
                is VttResponse.Text -> if (resp.body == lastVtt) {
                    ThumbnailFetch.Unchanged
                } else {
                    thumbnailCues = withContext(Dispatchers.Default) { parseThumbnailVttText(resp.body, baseUrl) }
                    lastVtt = resp.body
                    ThumbnailFetch.Changed
                }
            }
            delay(poll.after(fetch) ?: return@LaunchedEffect)
        }
    }

    // A debrid stream is analysed while it plays, so a session that started with no
    // segments and no thumbnails is the normal case. Its events are addressed to the
    // session rather than a file id — there is no library file behind it.
    LaunchedEffect(state.isDebrid, state.sessionId) {
        if (!state.isDebrid) return@LaunchedEffect
        val sid = state.sessionId ?: return@LaunchedEffect
        api.debridSessionEvents(sid).collect { frame ->
            // Guarded whole: the key does not change for the life of the session, so an
            // unexpected payload would otherwise end the collector for good.
            runCatching {
                when (frame.kind) {
                    "skip_segments_ready" -> {
                        val segments = frame.data["segments"] ?: return@runCatching
                        val parsed = api.decodeSkipSegments(segments)
                        if (parsed.isNotEmpty()) state.skipSegments = parsed
                    }
                    // The event carries the URL, but it is the same one the poll below
                    // builds — so use the event only as the trigger and let the existing
                    // helper resolve it and confirm the file is really servable.
                    "thumbnails_ready" -> (api.checkThumbnailsReady(sid) as? ThumbnailsProbe.Ready)?.let {
                        state.thumbnailsUrl = it.url
                    }
                }
            }
        }
    }

    // One library stream serves two listeners, so a title holds one connection, not two.
    //
    // Skip detection finishes minutes after a file is ingested and people press play
    // the moment the episode appears, so a session routinely fetches its segments before
    // they exist and then holds that empty list for the whole watch. The server pushes
    // them when they land; adopt the ones addressed to this file.
    //
    // Cross-device handoff: learn where this title stands on open and on every episode
    // change, then listen. Each reconnect re-asks, because the save that matters most is
    // the one made while this device was asleep. See PlaybackHandoff.kt.
    LaunchedEffect(mediaId, mediaType) {
        val handsOff = mediaType == "movie" || mediaType == "episode"
        if (!handsOff) snapshotFlow { state.fileId }.first { it != null }
        val isPlayingNow = { player.isPlaying.value }
        val secondsNow = { (state.timeline.toAbsoluteMs(player.currentMs.value) / 1000).toInt() }
        var connectedOnce = false
        api.libraryEvents().collect { frame ->
            // Guarded whole: the key does not change for the life of the title, so one frame
            // in an unexpected shape would otherwise take the collector down for good.
            runCatching {
                when (frame.kind) {
                    "skip_segments_ready" -> {
                        val fileId = state.fileId ?: return@runCatching
                        if (frame.data["file_id"]?.jsonPrimitive?.contentOrNull != fileId) return@runCatching
                        val segments = frame.data["segments"] ?: return@runCatching
                        // Detection is season-scoped, so this fires for every sibling episode
                        // too — the file id filter above keeps someone else's segments out.
                        val parsed = api.decodeSkipSegments(segments)
                        if (parsed.isNotEmpty()) state.skipSegments = parsed
                    }
                    dev.kobbler.app.data.STREAM_CONNECTED -> if (handsOff) {
                        state.syncHandoff(api, baseline = !connectedOnce, isPlayingNow, secondsNow)
                        connectedOnce = true
                    }
                    "watch_progress" -> if (handsOff) {
                        api.decodeWatchProgress(frame.data)?.let {
                            state.considerHandoff(it, isPlayingNow(), secondsNow())
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(state.sessionId, state.thumbnailsUrl) {
        val sid = state.sessionId ?: return@LaunchedEffect
        if (state.thumbnailsUrl != null) return@LaunchedEffect
        awaitThumbnails(probe = { api.checkThumbnailsReady(sid) })?.let { state.thumbnailsUrl = it }
    }

    val saveAndBack = {
        val pos = state.timeline.toAbsoluteMs(player.currentMs.value)
        if (pos > 5000) {
            state.teardownScope.launch { state.reportProgress(api, mediaType, mediaId, pos) }
        }
        onBack()
    }

    val isPlaying by player.isPlaying.collectAsState()
    val paused = player.state.collectAsState().value == PlayerState.PAUSED

    // Everything below this line is in absolute media time; the player's own clock only
    // reappears at seekTo. See [PlayerTimeline].
    val timeline = state.timeline
    val position = remember(player, timeline) { player.currentMs.mapState(timeline::toAbsoluteMs) }
    val playerDurationMs by player.durationMs.collectAsState()
    val durationMs = timeline.absoluteDurationMs(playerDurationMs)
    val seekTo: (Long) -> Unit = { absoluteMs -> seekPlayerTo(timeline.toPlayerMs(absoluteMs)) }
    // Credits usually run to EOF, and a seek onto the very end of a stream is one VLC
    // drops — the playhead blinks and stays put. Ending the title is what was asked for.
    // Paused first so a film's exit stops rather than carrying the credits into the mini player.
    val skipPast: (SkipSegment) -> Unit = { seg ->
        val endMs = (seg.endSeconds * 1000).toLong()
        if (skipRunsToEnd(endMs, durationMs)) {
            player.pause()
            scope.launch { finishTitle() }
        } else {
            seekTo(endMs)
        }
    }
    val vlcAudioTracks by player.audioTracks.collectAsState()
    val vlcSelectedAudioTrack by player.selectedAudioTrack.collectAsState()
    val vlcSubtitleTracks by player.subtitleTracks.collectAsState()
    val vlcSelectedSubtitleTrack by player.selectedSubtitleTrack.collectAsState()
    val playbackSpeed by player.playbackSpeed.collectAsState()
    val aspectRatio by player.aspectRatio.collectAsState()
    val playerVideoSize by player.videoSize.collectAsState()

    // Keyed on the stream and its shape, not on the title: one live session plays the
    // tuner's broadcast and, behind the live edge, the server's transcode of it, and the
    // two are not the same geometry. Keying this per title left a choice made about the
    // broadcast applied to a stream that had never been asked about.
    var aspectAppliedFor by remember { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(state.lastStreamUrl, state.videoWidth, state.videoHeight, playerVideoSize) {
        val source = state.lastStreamUrl ?: return@LaunchedEffect
        if (aspectAppliedFor?.first == source) return@LaunchedEffect
        val w = state.videoWidth ?: playerVideoSize.first.takeIf { it > 0 } ?: return@LaunchedEffect
        val h = state.videoHeight ?: playerVideoSize.second.takeIf { it > 0 } ?: return@LaunchedEffect
        val bucket = aspectBucket(w, h) ?: return@LaunchedEffect
        val previous = aspectAppliedFor
        aspectAppliedFor = source to bucket
        // A new stream of the same shape keeps whatever is set — including a choice the
        // viewer made by hand this session, which is deliberately not written down.
        if (previous?.second == bucket) return@LaunchedEffect
        val target = Settings.getString(ASPECT_BUCKET_KEY_PREFIX + bucket)
            ?.let { name -> dev.kobbler.kobplayer.AspectRatio.entries.firstOrNull { it.name == name } }
            ?: dev.kobbler.kobplayer.AspectRatio.FIT
        if (target != player.aspectRatio.value) player.setAspectRatio(target)
    }

    val onSetAspectRatioPersisted: (dev.kobbler.kobplayer.AspectRatio) -> Unit = { ratio ->
        player.setAspectRatio(ratio)
        if (ratio != dev.kobbler.kobplayer.AspectRatio.STRETCH) {
            val w = state.videoWidth ?: player.videoSize.value.first.takeIf { it > 0 }
            val h = state.videoHeight ?: player.videoSize.value.second.takeIf { it > 0 }
            val bucket = if (w != null && h != null) aspectBucket(w, h) else null
            if (bucket != null) {
                Settings.putString(ASPECT_BUCKET_KEY_PREFIX + bucket, ratio.name)
            }
        }
    }

    val useServerAudio = state.serverAudioTracks.size > 1
    val audioTracks: List<TrackInfo> = if (useServerAudio) {
        state.serverAudioTracks.map { it.toTrackInfo() }
    } else vlcAudioTracks
    val selectedAudioTrack: TrackInfo? = if (useServerAudio) {
        state.selectedServerAudioIndex?.let { idx ->
            state.serverAudioTracks.find { it.streamIndex == idx }?.toTrackInfo()
        }
    } else vlcSelectedAudioTrack
    var audioSwitching by remember { mutableStateOf(false) }
    val isDirectPlayAudio = useServerAudio
        && vlcAudioTracks.size == state.serverAudioTracks.size
        && vlcAudioTracks.isNotEmpty()
    val onSelectAudioTrack: (TrackInfo?) -> Unit = { track ->
        when {
            isDirectPlayAudio && track != null -> {
                val streamIdx = track.id.toIntOrNull()
                if (streamIdx != null && streamIdx != state.selectedServerAudioIndex) {
                    val nth = state.serverAudioTracks.indexOfFirst { it.streamIndex == streamIdx }
                    val targetVlc = vlcAudioTracks.getOrNull(nth)
                    if (targetVlc != null) {
                        player.selectAudioTrack(targetVlc)
                        state.selectedServerAudioIndex = streamIdx
                    }
                }
            }
            useServerAudio -> {
                val sid = state.sessionId
                val streamIdx = track?.id?.toIntOrNull()
                if (sid != null && streamIdx != null && streamIdx != state.selectedServerAudioIndex) {
                    audioSwitching = true
                    scope.launch {
                        switchAudioAndResume(api, player, state, sid, streamIdx)
                        audioSwitching = false
                    }
                }
            }
            else -> player.selectAudioTrack(track)
        }
    }

    val renderableServerSubs = state.renderableServerSubtitles
    val useServerSubs = renderableServerSubs.isNotEmpty()
    val subtitleTracks: List<TrackInfo> = if (useServerSubs) {
        renderableServerSubs.map { it.toSubtitleTrackInfo() }
    } else vlcSubtitleTracks
    val selectedSubtitleTrack: TrackInfo? = if (useServerSubs) {
        state.selectedServerSubtitleTrackId?.let { id ->
            renderableServerSubs.firstOrNull { it.trackId == id }?.toSubtitleTrackInfo()
        }
    } else vlcSelectedSubtitleTrack
    val onSelectSubtitleTrack: (TrackInfo?) -> Unit = { track ->
        if (useServerSubs) {
            player.selectSubtitleTrack(null)
            state.selectedServerSubtitleTrackId = track?.id
        } else {
            player.selectSubtitleTrack(track)
        }
    }

    val skipSegments = state.skipSegments
    val chapters = state.chapters

    val activeSkip by player.currentMs.collectAsPlayheadState(skipSegments, timeline) { ms ->
        val posSeconds = timeline.toAbsoluteMs(ms) / 1000
        skipSegments.firstOrNull { seg -> posSeconds >= seg.startSeconds && posSeconds < seg.endSeconds }
    }

    val chapterMarkers: List<Float> = remember(durationMs, chapters) {
        if (durationMs <= 0) emptyList()
        else chapters.map { (it.startSeconds * 1000 / durationMs).toFloat() }.filter { it in 0.01f..0.99f }
    }

    val segmentRegions: List<SeekBarRegion> = remember(durationMs, skipSegments) {
        if (durationMs <= 0) emptyList()
        else skipSegments.filter(::isVisibleSkipSegment).mapNotNull { seg ->
            val base = SEGMENT_COLORS[seg.segmentType] ?: return@mapNotNull null
            SeekBarRegion(
                startFraction = (seg.startSeconds * 1000 / durationMs).toFloat().coerceIn(0f, 1f),
                endFraction = (seg.endSeconds * 1000 / durationMs).toFloat().coerceIn(0f, 1f),
                color = base.copy(alpha = 0.55f),
            )
        }.filter { it.endFraction > it.startFraction }
    }

    val overlayChapters = remember(chapters, skipSegments) {
        chapters.map { ch ->
            OverlayChapter(
                title = ch.title ?: "Chapter ${ch.index + 1}",
                startMs = (ch.startSeconds * 1000).toLong(),
            )
        } + skipSegments.filter(::isVisibleSkipSegment).map { seg ->
            OverlayChapter(
                title = segmentTitle(seg),
                startMs = (seg.startSeconds * 1000).toLong(),
                endMs = (seg.endSeconds * 1000).toLong(),
                detected = true,
            )
        }
    }

    var showSubtitleSearch by remember { mutableStateOf(false) }
    var railOpen by remember { mutableStateOf(false) }
    val focusScope = LocalFocusScope.current

    // Every layer the app draws over the player. Each owns the D-pad while it is up, so
    // the chrome underneath leaves focus traversal ([focusInert]) and stays put rather
    // than timing out — the control a layer was opened from has to still be there when it
    // closes. A tune's own preparing shell counts: it covers the player too.
    val overlaid = showSubtitleSearch || state.isPreparing || railOpen

    // The rail registers into this same scope rather than hosting its own, so entry focus
    // for it is this scope's preferred pass — which lands on the current channel's card.
    LaunchedEffect(railOpen) { if (railOpen) focusScope.focusFirst() }

    val isLiveTv = mediaType == "livetv"
    LaunchedEffect(isLiveTv) { if (!isLiveTv) railOpen = false }
    InterceptBack(enabled = railOpen, onBack = { railOpen = false })

    val openChannelPanel = { railOpen = true }

    var recordRequested by remember(state.liveSessionId) { mutableStateOf(false) }
    if (isLiveTv) {
        LiveBufferPoll(state, api, player)
        LiveNowPoll(state, api)
    }

    val enterTimeshiftAt: (Int?) -> Unit = { at ->
        scope.launch { enterLiveTimeshift(state, player, api, seekToSec = at) }
    }

    // The owner's posture decides how an ad break is offered; auto is the only mode that
    // moves playback on its own, and it is never the client's default.
    LaunchedEffect(activeSkip, state.adPosture, state.adSkipDisabled) {
        val seg = activeSkip ?: return@LaunchedEffect
        if (seg.segmentType != "ad_break") return@LaunchedEffect
        if (!shouldAutoSkipAd(state.adPosture, state.adSkipDisabled)) return@LaunchedEffect
        skipPast(seg)
    }

    val colors = KobappTheme.colors
    val type = KobappTheme.type
    val dimens = KobappTheme.dimens
    val sectionAccent = colors.accentFor(section)

    // A live tune has a timeline after all: the ring buffer behind the live edge. The raw
    // stream cannot seek in it, so the bar is drawn over the buffer with the playhead
    // pinned right, and dragging back drops into the playlist at that offset.
    val liveBufferMs = ((state.liveBuffer?.liveEdgeSeconds ?: 0.0) * 1000).toLong()
    // Keyed on the playlist, which the tune itself reports, and not on the buffer, which
    // a poll reports up to ten seconds later. Deciding on the buffer meant every tune and
    // every channel flip opened on the LIVE badge and then rebuilt the whole control row
    // into a scrubber underneath the viewer — one player that looked like two. Without a
    // playlist there is genuinely nothing to seek into, and that is a property of the
    // tune, so the badge stays put for the whole of one.
    val liveEdgeBar = isLiveTv && !state.liveTimeshift && state.livePlaylistUrl != null

    val overlayDurationMs = if (liveEdgeBar) liveBufferMs else durationMs
    val overlayPosition: StateFlow<Long> = remember(liveEdgeBar, liveBufferMs, position) {
        if (liveEdgeBar) MutableStateFlow(liveBufferMs) else position
    }
    val overlaySeek: (Long) -> Unit = if (liveEdgeBar) ({ ms ->
        timeshiftTargetSeconds(ms, liveBufferMs)?.let(enterTimeshiftAt)
    }) else seekTo

    val liveRegions = if (isLiveTv) {
        listOfNotNull(liveProgrammeRegion(state, overlayDurationMs, sectionAccent)) + segmentRegions
    } else {
        segmentRegions
    }


    Box(Modifier.fillMaxSize().then(if (videoSurface == null) Modifier.background(colors.bg) else Modifier)) {
      // Player chrome stays composed under the overlays drawn on top of it — contain it.
      Box(Modifier.focusKeyGroup("player").focusInert(overlaid)) {
        if (videoSurface != null) videoSurface(Modifier.fillMaxSize())
        else VideoSurface(player, Modifier.fillMaxSize())
        StartBackdrop(state)

        if (!isInPip) {
            PlayerOverlay(
                title = state.title,
                logoUrl = state.logoUrl,
                subtitle = state.subtitle,
                isPlaying = isPlaying,
                paused = paused,
                position = overlayPosition,
                durationMs = overlayDurationMs,
                onPlayPause = { state.togglePlayback(player, api, scope) },
                onSeek = overlaySeek,
                onBack = saveAndBack,
                // A TV player has no windowed mode to toggle into, so the control is a
                // dead end that still costs a D-pad stop on the way to the real ones.
                onToggleFullscreen = onToggleFullscreen.takeIf { formFactor != FormFactor.TV },
                isFullscreen = isFullscreen,
                audioTracks = audioTracks,
                selectedAudioTrack = selectedAudioTrack,
                onSelectAudioTrack = onSelectAudioTrack,
                subtitleTracks = subtitleTracks,
                selectedSubtitleTrack = selectedSubtitleTrack,
                onSelectSubtitleTrack = onSelectSubtitleTrack,
                playbackSpeed = playbackSpeed,
                onSetPlaybackSpeed = { player.setPlaybackSpeed(it) },
                aspectRatio = aspectRatio,
                onSetAspectRatio = onSetAspectRatioPersisted,
                chapterMarkers = chapterMarkers,
                regions = liveRegions,
                chapters = overlayChapters,
                isLive = state.isLive && !state.liveTimeshift && !liveEdgeBar,
                loading = state.isStarting && !state.isPreparing,
                thumbnailPreview = if (thumbnailCues.isNotEmpty()) { seekFraction ->
                    ThumbnailPreview(
                        cues = thumbnailCues,
                        durationMs = overlayDurationMs,
                        seekFraction = seekFraction,
                    )
                } else null,
                chapterThumbnail = if (thumbnailCues.isNotEmpty()) { startMs ->
                    ThumbnailFrame(cues = thumbnailCues, atMs = startMs, width = 200.dp)
                } else null,
                sideGestures = if (formFactor == FormFactor.Phone) {
                    dev.kobbler.kobplayer.ui.SideGestureConfig(
                        brightness = playerBrightness,
                        onBrightnessChange = { v ->
                            playerBrightness = v
                        },
                        volume = playerVolume,
                        maxVolume = player.maxVolume,
                        onVolumeChange = { v -> player.setVolume(v) },
                        showHint = hintActive,
                    )
                } else null,
                onFindSubtitles = if (state.sessionId != null && mediaType in listOf("movie", "episode")) {
                    { showSubtitleSearch = true }
                } else null,
                // Nothing airs ahead of the live edge, so the press that would have
                // seeked there opens the channel list instead — the one thing a remote
                // reaches for mid-programme.
                onSeekPastEnd = openChannelPanel.takeIf { isLiveTv },
                overlaid = overlaid,
                extraControls = {
                    // A TV is a cast target, not a sender, and live has no receiver-side
                    // story — the channel is this device's tuner.
                    if (formFactor != FormFactor.TV && !isLiveTv) {
                        dev.kobbler.app.platform.CastButton(
                            Modifier.focusKey("ctrl.cast").tvAutoGlow(cornerRadiusDp = 999f, scale = 1.08f),
                            colors.textHi,
                        )
                    }
                    val skipPrevious = state.onSkipPrevious
                    if (skipPrevious != null) {
                        IconButton(
                            onClick = skipPrevious,
                            modifier = Modifier
                                .focusKey("ctrl.prevepisode")
                                .tvAutoGlow(cornerRadiusDp = 999f, scale = 1.08f),
                        ) {
                            Icon(Icons.Outlined.SkipPrevious, "Previous episode", tint = colors.textHi)
                        }
                    }
                    val skipNext = state.onSkipNext
                    if (skipNext != null) {
                        IconButton(
                            onClick = skipNext,
                            modifier = Modifier
                                .focusKey("ctrl.nextepisode")
                                .tvAutoGlow(cornerRadiusDp = 999f, scale = 1.08f),
                        ) {
                            Icon(Icons.Outlined.SkipNext, "Next episode", tint = colors.textHi)
                        }
                    }
                    if (isLiveTv) {
                        LiveEdgeIndicator(
                            state = state,
                            accent = sectionAccent,
                            playerMs = player.currentMs,
                            durationMs = durationMs,
                            onJumpToLive = { seekPlayerTo(player.durationMs.value) },
                        )
                        val liveSid = state.liveSessionId
                        IconButton(
                            onClick = {
                                val sid = liveSid ?: return@IconButton
                                recordRequested = true
                                scope.launch {
                                    runCatching { api.recordLiveSession(sid, state.liveNowGuideId) }
                                        .onFailure { recordRequested = false }
                                }
                            },
                            enabled = liveSid != null && !recordRequested,
                            modifier = Modifier
                                .focusKey("ctrl.record")
                                .tvAutoGlow(cornerRadiusDp = 999f, scale = 1.08f),
                        ) {
                            Icon(
                                Icons.Outlined.FiberManualRecord,
                                if (recordRequested) "Recording" else "Record",
                                tint = if (recordRequested) colors.err else colors.textHi,
                            )
                        }
                        IconButton(
                            onClick = openChannelPanel,
                            modifier = Modifier
                                .focusKey("ctrl.channels")
                                .tvAutoGlow(cornerRadiusDp = 999f, scale = 1.08f),
                        ) {
                            Icon(Icons.Outlined.Tv, "Channels", tint = colors.textHi)
                        }
                    }
                    if (state.isDebrid && state.onSwitchStream != null) {
                        IconButton(
                            onClick = { state.onSwitchStream?.invoke() },
                            modifier = Modifier
                                .focusKey("ctrl.switchstream")
                                .tvAutoGlow(cornerRadiusDp = 999f, scale = 1.08f),
                        ) {
                            Icon(Icons.Outlined.SwapHoriz, "Switch stream", tint = colors.textHi)
                        }
                    }
                    val keepSession = state.sessionId?.takeIf { state.isDebrid }
                    if (keepSession != null) {
                        val kept = state.keeps[keepSession]
                        IconButton(
                            onClick = { state.keep(api, keepSession) },
                            enabled = kept == null || kept.state == KeepState.Failed,
                            modifier = Modifier
                                .focusKey("ctrl.keep")
                                .tvAutoGlow(cornerRadiusDp = 999f, scale = 1.08f),
                        ) {
                            Icon(
                                if (kept?.state == KeepState.Done) Icons.Outlined.LibraryAddCheck else Icons.Outlined.LibraryAdd,
                                keepLabel(kept),
                                tint = colors.textHi,
                            )
                        }
                    }
                },
            )

            StartingNote(
                state,
                Modifier.align(Alignment.BottomCenter).safeContentPadding().padding(bottom = dimens.s6),
            )
            PlayerNote(
                state,
                Modifier.align(Alignment.TopCenter).safeContentPadding().padding(top = dimens.s6),
            )

            val skip = activeSkip
            val adShadeOnly = skip?.segmentType == "ad_break" && state.adPosture != LiveAdPostureButton
            val offer = state.handoff
            if (offer != null) {
                HandoffCard(
                    progress = offer,
                    otherEpisode = offer.mediaId != mediaId,
                    stayCountdown = state.handoffStayCountdown,
                    accent = sectionAccent,
                    onJump = { state.followHandoff(seekToMs = seekTo, play = { player.play() }) },
                    onStay = { state.stayHere() },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .safeContentPadding()
                        .padding(end = dimens.s5, bottom = 80.dp),
                )
            } else if (skip != null && !adShadeOnly) {
                val label = when (skip.segmentType) {
                    "intro" -> "Skip Intro"
                    "credits" -> "Skip Credits"
                    "recap" -> "Skip Recap"
                    "ad_break" -> "Skip Ads"
                    else -> "Skip"
                }
                KobButton(
                    text = label,
                    onClick = { skipPast(skip) },
                    style = KobButtonStyle.Accent,
                    accent = sectionAccent,
                    leading = {
                        Icon(
                            Icons.Outlined.FastForward,
                            contentDescription = null,
                            tint = colors.bg,
                        )
                    },
                    modifier = Modifier
                        .focusKey("ctrl.skip")
                        .align(Alignment.BottomEnd)
                        .safeContentPadding()
                        .padding(end = dimens.s5, bottom = 80.dp),
                )
            }
        }
      }

        if (railOpen) {
            LiveChannelRail(
                api = api,
                currentChannelId = state.mediaId,
                accent = sectionAccent,
                formFactor = formFactor,
                tunersInUse = state.liveTunersInUse,
                tunerCount = state.liveTunerCount,
                modifier = Modifier
                    .align(if (formFactor == FormFactor.Phone) Alignment.BottomCenter else Alignment.CenterEnd)
                    .safeContentPadding(),
                onPick = { channel -> scope.launch { switchLiveChannel(channel, player, state, api) } },
                onOpenGuide = onOpenGuide?.let { open -> { railOpen = false; open() } },
                onDismiss = { railOpen = false },
            )
        }

        // Deliberately not the isPreparing scrim: the swap is short and the viewer is
        // still watching the frame behind it, so this only names what is happening.
        val liveSeekingLabel = state.liveSeekingLabel
        if (liveSeekingLabel != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dimens.s2),
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(999.dp))
                    .background(colors.bg.copy(alpha = 0.82f))
                    .padding(horizontal = dimens.s4, vertical = dimens.s3),
            ) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = sectionAccent,
                )
                Text(liveSeekingLabel, style = type.bodySm, color = colors.textHi)
            }
        }

        if (state.isPreparing) {
            PreparingShell(
                state = state,
                accent = sectionAccent,
                inert = showSubtitleSearch || railOpen,
                onCancel = {
                    val onCancel = state.onPreparingCancel
                    if (onCancel != null) onCancel() else state.stop(player, api, scope)
                },
            )
        }

        if (showSubtitleSearch && state.sessionId != null) {
            SubtitleSearchOverlay(
                api = api,
                mediaType = mediaType,
                mediaId = mediaId,
                sessionId = state.sessionId!!,
                onDismiss = { showSubtitleSearch = false },
                onSubtitleAdded = { dto ->
                    val existing = state.serverSubtitleTracks.filterNot { it.trackId == dto.trackId }
                    state.serverSubtitleTracks = existing + dto
                    if (dto.trackId != null && !dto.url.isNullOrBlank()) {
                        player.selectSubtitleTrack(null)
                        state.selectedServerSubtitleTrackId = dto.trackId
                    }
                },
            )
        }
    }
}

private fun AudioTrackDto.toTrackInfo(): TrackInfo {
    val label = buildString {
        if (!title.isNullOrBlank()) append(title)
        else if (!language.isNullOrBlank()) append(language)
        else append("Track ${streamIndex}")
        if (codec.isNotBlank()) append(" ($codec)")
        val chLabel = when (channels) { 1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> null }
        if (chLabel != null) append(" $chLabel")
    }
    return TrackInfo(id = streamIndex.toString(), label = label, language = language)
}

private fun dev.kobbler.app.data.PlaybackSubtitleTrackDto.toSubtitleTrackInfo(): TrackInfo {
    val label = buildString {
        when {
            !title.isNullOrBlank() -> append(title)
            !language.isNullOrBlank() -> append(language.uppercase())
            else -> append("Track")
        }
        if (isDefault) append(" · default")
    }
    return TrackInfo(id = trackId ?: "", label = label, language = language)
}

/**
 * Restart the session on another audio track and put the playhead back where it was.
 *
 * The server honours `resume_time` by rebasing the playlist, so the position that goes
 * out is absolute and the one that comes back has to be read against the new timeline.
 */
internal suspend fun switchAudioAndResume(
    api: KobblerApi,
    player: Player,
    state: GlobalPlayerState,
    sessionId: String,
    audioStreamIndex: Int,
) {
    val posMs = state.timeline.toAbsoluteMs(player.currentMs.value)
    val newSession = api.switchAudioTrack(
        sessionId, audioStreamIndex, (posMs / 1000).toInt(), state.fileId,
    ) ?: return
    state.selectedServerAudioIndex = audioStreamIndex
    state.serverAudioTracks = newSession.audioTracks
    state.sessionId = newSession.sessionId
    state.timeline = PlayerTimeline.of(newSession.serverStartSeconds)
    val seekMs = state.timeline.toPlayerMs(posMs)
    player.load(
        dev.kobbler.kobplayer.MediaSource.Url(
            api.playerUrl(newSession.url),
            networkCacheMs = api.networkCacheMsFor(newSession.url),
            startTimeSec = (seekMs / 1000).toInt().takeIf { it > 0 },
        ),
    )
    // Through the player's own resume path, so the switch is checked and repaired like
    // any other start rather than silently dropping the viewer at the top of the episode.
    state.pendingSeekMs = seekMs
    state.playUnmuted(player)
}

