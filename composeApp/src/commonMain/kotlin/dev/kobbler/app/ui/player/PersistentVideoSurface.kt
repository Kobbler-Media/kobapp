package dev.kobbler.app.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.platform.PlaybackLifecycle
import dev.kobbler.app.ui.components.tvAutoGlow
import dev.kobbler.kobplayer.Player
import dev.kobbler.kobplayer.VideoSurface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private const val SNAP_MS = 250

/** Applies [fullscreenExit]; the two dismiss paths share it so they cannot disagree. */
/** Back out of the player: while a start is still being found it is called off, not just hidden. */
fun GlobalPlayerState.back(player: Player, api: KobblerApi, formFactor: FormFactor, scope: CoroutineScope) {
    if (isPreparing) cancelStart(player, api, scope) else leaveFullscreen(player, api, formFactor, scope)
}

fun GlobalPlayerState.cancelStart(player: Player, api: KobblerApi, scope: CoroutineScope) {
    val cancel = onPreparingCancel
    when {
        cancel != null -> cancel()
        mediaId == null -> cancelPreparing()
        else -> stop(player, api, scope)
    }
}

fun GlobalPlayerState.leaveFullscreen(
    player: Player,
    api: KobblerApi,
    formFactor: FormFactor,
    scope: CoroutineScope,
) {
    when (fullscreenExit(formFactor, player.isPlaying.value)) {
        FullscreenExit.ToMiniPlayer -> {
            isMiniPlayer = true
            isFullscreen = false
        }
        FullscreenExit.Stop -> stop(player, api, scope)
    }
}

@Composable
fun PersistentVideoSurface(
    player: Player,
    state: GlobalPlayerState,
    api: KobblerApi,
    formFactor: FormFactor,
    onToggleFullscreen: (() -> Unit)?,
    isFullscreen: Boolean,
    scope: CoroutineScope,
    miniBottomInset: androidx.compose.ui.unit.Dp = 0.dp,
    onOpenGuide: (() -> Unit)? = null,
    videoSurface: @Composable (Modifier) -> Unit = { VideoSurface(player, it) },
) {
    val cast = state.castSession
    if (!state.isVideoPlaying && cast == null) return

    if (cast != null) {
        Box(Modifier.fillMaxSize().then(if (miniBottomInset > 0.dp) Modifier.padding(bottom = miniBottomInset) else Modifier)) {
            CastMiniController(
                deviceName = cast.deviceName,
                title = cast.mediaTitle,
                subtitle = state.subtitle,
                isPlaying = cast.isPlaying,
                positionMs = cast.positionMs,
                durationMs = cast.durationMs,
                onPlayPause = { state.onCastPlayPause?.invoke() },
                onSeekTo = { ms -> state.onCastSeek?.invoke(ms) },
                onDisconnect = { state.onCastDisconnect?.invoke() },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        return
    }

    DisposableEffect(player) {
        PlaybackLifecycle.startBackgroundPlayback()
        onDispose {
            PlaybackLifecycle.stopBackgroundPlayback()
            // The fullscreen player keeps the transport wired on the way down so the mini
            // player still has one; this is the other end of that, and the only place
            // where playback is genuinely over.
            PlaybackLifecycle.releasePlayer(player)
        }
    }

    val activeMediaId = state.mediaId
    val activeMediaType = state.mediaType
    LaunchedEffect(activeMediaId, activeMediaType) {
        if (activeMediaId == null || activeMediaType == null) return@LaunchedEffect
        suspend fun save(mirror: Boolean) {
            val pos = state.timeline.toAbsoluteMs(player.currentMs.value)
            // A finished title has already saved itself as finished; a part-way save
            // over the top would put it back in progress at its credits.
            if (pos > 0 && player.state.value != dev.kobbler.kobplayer.PlayerState.FINISHED) {
                state.reportProgress(api, activeMediaType, activeMediaId, pos, mirror = mirror)
            }
        }
        val save: suspend () -> Unit = { save(mirror = true) }
        PlaybackExit.register(save)
        try {
            coroutineScope {
                // Gated on the playhead having moved (reportProgress skips a repeat), not on
                // isPlaying: a player whose state sat anywhere but PLAYING at each tick —
                // buffering, a stale state from the backend — went minutes without a save.
                launch {
                    while (true) {
                        kotlinx.coroutines.delay(10_000)
                        save(mirror = false)
                    }
                }
                // A pause is where a viewer most often walks away to another device.
                player.isPlaying.collect { playing -> if (!playing) save() }
            }
        } finally {
            PlaybackExit.unregister(save)
        }
    }

    // Pressing play past an open offer is a soft "stay here" — soft, because a remote's
    // play button is pressed by reflex. The card stays up long enough to change course;
    // until it goes, nothing is reported. Run here, not in the expanded player that draws
    // the card: played past from the mini player, the offer otherwise never resolved and
    // every save for the rest of the title was dropped.
    val isPlayingNow by player.isPlaying.collectAsState()
    LaunchedEffect(state.handoff, isPlayingNow) {
        state.handoffStayCountdown = null
        if (state.handoff == null || !isPlayingNow) return@LaunchedEffect
        for (left in HANDOFF_STAY_SECONDS downTo 1) {
            state.handoffStayCountdown = left
            kotlinx.coroutines.delay(1000)
        }
        state.handoffStayCountdown = null
        state.stayHere()
    }

    val useServerSubs = state.renderableServerSubtitles.isNotEmpty()
    // Re-asserted per media: the flag is sticky in the player, but a new session starts
    // with VLC free to pick its own track again.
    LaunchedEffect(useServerSubs, state.sessionId) {
        if (useServerSubs) player.selectSubtitleTrack(null)
    }
    val subtitleCues = rememberSubtitleCues(
        url = state.activeServerSubtitleUrl,
        client = api.httpClient,
        authToken = api.authHeaders["Authorization"]?.removePrefix("Bearer ") ?: "",
    )
    val isInPip by PlaybackLifecycle.isInPipModeFlow.collectAsState()

    val density = LocalDensity.current
    val miniWidthDp = 200.dp
    val miniHeightDp = 112.dp
    val miniMarginDp = 12.dp
    val miniWidthPx = with(density) { miniWidthDp.roundToPx() }
    val miniHeightPx = with(density) { miniHeightDp.roundToPx() }
    val miniMarginPx = with(density) { miniMarginDp.roundToPx() }

    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var snapLeft by remember { mutableStateOf(false) }
    var snapTop by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }
    var showMiniControls by remember { mutableStateOf(true) }

    val miniSnapX = if (snapLeft) miniMarginPx else (containerSize.width - miniWidthPx - miniMarginPx)
    val miniSnapY = if (snapTop) miniMarginPx else (containerSize.height - miniHeightPx - miniMarginPx)

    val mode = state.playerMode

    val targetRect: IntRect = remember(mode, containerSize, miniSnapX, miniSnapY) {
        when (mode) {
            PlayerMode.Fullscreen -> IntRect(0, 0, containerSize.width, containerSize.height)
            PlayerMode.Mini, PlayerMode.Pip, PlayerMode.Hidden ->
                IntRect(miniSnapX, miniSnapY, miniSnapX + miniWidthPx, miniSnapY + miniHeightPx)
        }
    }

    val animOffset = remember {
        Animatable(IntOffset(targetRect.left, targetRect.top), IntOffset.VectorConverter)
    }
    val animSize = remember {
        Animatable(IntSize(targetRect.width, targetRect.height), IntSize.VectorConverter)
    }
    val miniScope = rememberCoroutineScope()

    var dragJustEnded by remember { mutableStateOf(false) }
    SurfaceMorphEffect(
        containerSize = containerSize,
        targetRect = targetRect,
        offset = animOffset,
        size = animSize,
        // The drag handler owns the animatables while a drag is live, and owns the
        // release snap for one target after it.
        skipAnimation = {
            if (dragJustEnded) {
                dragJustEnded = false
                true
            } else isDragging
        },
        animates = { morphAnimates(animSize.value, mode, IntSize(miniWidthPx, miniHeightPx)) },
    )

    val isMini = mode == PlayerMode.Mini
    val isFull = mode == PlayerMode.Fullscreen

    val cornerDp = when (mode) {
        PlayerMode.Fullscreen, PlayerMode.Hidden -> 0.dp
        PlayerMode.Mini, PlayerMode.Pip -> 12.dp
    }
    val shadowDp = when (mode) {
        PlayerMode.Fullscreen, PlayerMode.Hidden -> 0.dp
        PlayerMode.Mini, PlayerMode.Pip -> 8.dp
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(if (isMini) Modifier.safeDrawingPadding() else Modifier)
            .then(if (isMini && miniBottomInset > 0.dp) Modifier.padding(bottom = miniBottomInset) else Modifier)
            .onSizeChanged { containerSize = it },
    ) {
        if (containerSize == IntSize.Zero) return@Box

        // Read in placement and measurement, not here: a morph or drag moves these every
        // frame, and read in composition each frame recomposed the whole player.
        val surfaceModifier = Modifier
            .offset {
                if (isMini && isDragging) {
                    IntOffset(
                        (miniSnapX + dragOffsetX.toInt()).coerceIn(0, (containerSize.width - miniWidthPx).coerceAtLeast(0)),
                        (miniSnapY + dragOffsetY.toInt()).coerceIn(0, (containerSize.height - miniHeightPx).coerceAtLeast(0)),
                    )
                } else animOffset.value
            }
            .layout { measurable, _ ->
                val size = animSize.value
                val placeable = measurable.measure(Constraints.fixed(size.width, size.height))
                layout(size.width, size.height) { placeable.place(0, 0) }
            }
            .then(if (shadowDp > 0.dp) Modifier.shadow(shadowDp, RoundedCornerShape(cornerDp)) else Modifier)
            .then(if (cornerDp > 0.dp) Modifier.clip(RoundedCornerShape(cornerDp)) else Modifier)
            .background(Color.Black)
            .then(
                if (isMini) Modifier
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { isDragging = true },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragOffsetX += dragAmount.x
                                dragOffsetY += dragAmount.y
                            },
                            onDragEnd = {
                                val releaseX = (miniSnapX + dragOffsetX.toInt()).coerceIn(0, (containerSize.width - miniWidthPx).coerceAtLeast(0))
                                val releaseY = (miniSnapY + dragOffsetY.toInt()).coerceIn(0, (containerSize.height - miniHeightPx).coerceAtLeast(0))
                                val centerX = releaseX + miniWidthPx / 2
                                val centerY = releaseY + miniHeightPx / 2
                                snapLeft = centerX < containerSize.width / 2
                                snapTop = centerY < containerSize.height / 2
                                dragOffsetX = 0f
                                dragOffsetY = 0f
                                dragJustEnded = true
                                miniScope.launch {
                                    animOffset.snapTo(IntOffset(releaseX, releaseY))
                                    isDragging = false
                                    val targetX = if (snapLeft) miniMarginPx else (containerSize.width - miniWidthPx - miniMarginPx)
                                    val targetY = if (snapTop) miniMarginPx else (containerSize.height - miniHeightPx - miniMarginPx)
                                    animOffset.animateTo(IntOffset(targetX, targetY), tween(SNAP_MS))
                                }
                            },
                            onDragCancel = {
                                dragOffsetX = 0f
                                dragOffsetY = 0f
                                isDragging = false
                            },
                        )
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { showMiniControls = !showMiniControls },
                            onDoubleTap = {
                                state.isFullscreen = true
                                state.isMiniPlayer = false
                            },
                        )
                    }
                else Modifier,
            )

        Box(surfaceModifier) {
            videoSurface(Modifier.fillMaxSize())

            if (!isInPip) {
                SubtitleOverlay(
                    cues = subtitleCues,
                    playerMs = player.currentMs,
                    timeline = state.timeline,
                    formFactor = formFactor,
                    compact = isMini,
                )
            }

            if (isMini) {
                MiniProgress(player, state.timeline, Modifier.fillMaxWidth().height(2.dp).align(Alignment.BottomCenter))

                // The fullscreen player has the preparing overlay for this; the mini one
                // has 200dp and no room to explain itself, so it says only that something
                // is happening. Gated on the same delay, so a reconnect that lands in
                // under a second never draws anything at all.
                if (state.recoveryNotice) {
                    Box(
                        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                    }
                }

                if (showMiniControls) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))) {
                        IconButton(
                            onClick = { state.togglePlayback(player, api, scope) },
                            modifier = Modifier.align(Alignment.Center).size(40.dp)
                                .tvAutoGlow(cornerRadiusDp = 999f, scale = 1.1f),
                        ) {
                            MiniPlayPauseIcon(player)
                        }
                        IconButton(
                            onClick = { state.stop(player, api, scope) },
                            modifier = Modifier.align(Alignment.TopEnd).size(36.dp)
                                .tvAutoGlow(cornerRadiusDp = 999f, scale = 1.1f),
                        ) {
                            Icon(Icons.Outlined.Close, "Close", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                        IconButton(
                            onClick = {
                                state.isFullscreen = true
                                state.isMiniPlayer = false
                            },
                            modifier = Modifier.align(Alignment.TopStart).size(36.dp)
                                .tvAutoGlow(cornerRadiusDp = 999f, scale = 1.1f),
                        ) {
                            Icon(Icons.Outlined.Fullscreen, "Expand", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }

        if (isFull) {
            FullscreenVideoPlayer(
                player = player,
                state = state,
                api = api,
                formFactor = formFactor,
                onBack = { state.back(player, api, formFactor, scope) },
                onFinished = state.onFinished,
                onToggleFullscreen = onToggleFullscreen,
                isFullscreen = isFullscreen,
                videoSurface = { /* surface managed here, not by FullscreenVideoPlayer */ },
                section = when (state.mediaType) {
                    "livetv", "recording" -> dev.kobbler.app.theme.Section.LiveTv
                    "iptv" -> dev.kobbler.app.theme.Section.Iptv
                    else -> dev.kobbler.app.theme.Section.Movies
                },
                onOpenGuide = onOpenGuide,
            )
        }
    }
}

@Composable
private fun MiniProgress(player: Player, timeline: PlayerTimeline, modifier: Modifier) {
    val playerMs = player.currentMs.collectAsState()
    val playerDurationMs = player.durationMs.collectAsState()
    LinearProgressIndicator(
        progress = {
            val durationMs = timeline.absoluteDurationMs(playerDurationMs.value)
            if (durationMs > 0) timeline.toAbsoluteMs(playerMs.value).toFloat() / durationMs.toFloat() else 0f
        },
        modifier = modifier,
        color = Color.White,
        trackColor = Color.White.copy(alpha = 0.2f),
    )
}

@Composable
private fun MiniPlayPauseIcon(player: Player) {
    val isPlaying by player.isPlaying.collectAsState()
    Icon(
        if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
        "Play/Pause", tint = Color.White, modifier = Modifier.size(28.dp),
    )
}
