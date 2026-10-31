package dev.kobbler.kobplayer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import dev.kobbler.kobplayer.AspectRatio
import dev.kobbler.kobplayer.TrackInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Color scheme for the video player overlay. Parameterized so apps can brand it.
 */
data class PlayerColors(
    val primary: Color = Color.White,
    val primaryDim: Color = Color.White.copy(alpha = 0.7f),
    val primaryFaint: Color = Color.White.copy(alpha = 0.4f),
    val accent: Color = Color(0xFF8AB4F8),
    val controlBackground: Color = Color.Black.copy(alpha = 0.4f),
    val seekBarActive: Color = Color.White,
    val seekBarInactive: Color = Color.White.copy(alpha = 0.25f),
    val menuBackground: Color = Color(0xF0181818),
)

/**
 * Generic fullscreen video player overlay with transport controls, seek bar,
 * track/speed/aspect selectors, and optional branding.
 *
 * YouTube-style tap behavior: first tap shows/hides controls.
 * Play/pause requires hitting the button.
 */
/**
 * Chapter info for the chapter list overlay.
 */
data class OverlayChapter(
    val title: String,
    val startMs: Long,
    val endMs: Long? = null,
    val detected: Boolean = false,
)

/**
 * Optional brightness/volume drag gestures on the player overlay.
 *
 * When provided, vertical drags on the left half adjust [brightness] (0..1) and
 * drags on the right half adjust [volume] (0..[maxVolume]). The owner is
 * responsible for applying the new values (e.g. via window flags / player API)
 * and persisting them. Setting [showHint] true briefly flashes both indicator
 * bars so users discover the gestures exist; flip it back to false after the
 * flash completes.
 */
data class SideGestureConfig(
    val brightness: Float,
    val onBrightnessChange: (Float) -> Unit,
    val volume: Float,
    val maxVolume: Float,
    val onVolumeChange: (Float) -> Unit,
    val showHint: Boolean = false,
)

internal enum class DragSide { Left, Right }

/**
 * What the player chrome keeps clear of: system bars and a cutout, deliberately not
 * [safeDrawing].
 *
 * safeDrawing folds in the IME, and the transport is anchored to the bottom edge, so an
 * IME inset lands on it whole. A keyboard opened on some other screen — the guide's
 * search field is the one that does it — leaves an inset behind that the player then
 * treats as furniture to avoid, and the bar sits a third of the way up the picture with
 * the film still playing underneath it.
 *
 * Mirrored, so the chrome sits the same distance in from opposite edges: a phone held
 * with its cutout at the top pushed the title bar down by the notch while the transport
 * sat flush on the bottom edge, which reads as off-centre.
 */
private val playerChromeInsets: WindowInsets
    @Composable
    get() = WindowInsets.systemBars.union(WindowInsets.displayCutout).mirrored()

/**
 * Room inside a dropdown for a focused item's ring to draw into.
 *
 * The ring is drawn outside the element it belongs to and a menu clips to its own
 * surface, so an item that spans the full menu width loses its left and right edges to
 * that clip and the selected row reads as ringed on two sides only.
 */
private val MenuFocusGutter = 6.dp

/**
 * Supplies the host app's focus treatment for one control.
 *
 * The overlay lives in a module that cannot see the app's focus helpers, so every
 * interactive element asks for its decoration instead of drawing its own ring. [key]
 * is the restorable focus identity; the rest is shape.
 */
typealias ControlFocus = @Composable (key: String, cornerRadiusDp: Float, scale: Float) -> Modifier

// The live edge is a polled figure and the playhead chases it, so "at the end" is a
// second's grace rather than an equality.
private const val PastEndSlackMs = 1_000L

// Long enough for AnimatedVisibility to compose and lay out the transport, short enough
// not to fight a viewer who has already moved off it.
private const val EntryFocusFrames = 8

@Composable
fun VideoPlayerOverlay(
    title: String,
    subtitle: String? = null,
    logoUrl: String? = null,
    isPlaying: Boolean,
    /** Paused by the viewer, wherever from; unlike !isPlaying, not while buffering. */
    paused: Boolean = false,
    /**
     * Collected only while the controls are shown. A state read in composition even once
     * wakes the recomposer on every later write, so one held above the chrome cost a vsync
     * per tick for the rest of the title with nothing on screen reading it.
     */
    position: StateFlow<Long>,
    durationMs: Long,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onBack: () -> Unit,
    onToggleFullscreen: (() -> Unit)? = null,
    isFullscreen: Boolean = false,
    audioTracks: List<TrackInfo> = emptyList(),
    selectedAudioTrack: TrackInfo? = null,
    onSelectAudioTrack: (TrackInfo?) -> Unit = {},
    subtitleTracks: List<TrackInfo> = emptyList(),
    selectedSubtitleTrack: TrackInfo? = null,
    onSelectSubtitleTrack: (TrackInfo?) -> Unit = {},
    /** Opens the host app's subtitle search; shown as the last item of the subtitle menu. */
    onFindSubtitles: (() -> Unit)? = null,
    playbackSpeed: Float = 1.0f,
    onSetPlaybackSpeed: (Float) -> Unit = {},
    aspectRatio: AspectRatio = AspectRatio.FIT,
    onSetAspectRatio: (AspectRatio) -> Unit = {},
    chapterMarkers: List<Float> = emptyList(),
    regions: List<SeekBarRegion> = emptyList(),
    chapters: List<OverlayChapter> = emptyList(),
    colors: PlayerColors = PlayerColors(),
    modifier: Modifier = Modifier,
    isLive: Boolean = false,
    /**
     * The stream is still opening. Suppresses every control — they are drawn from a
     * position and a duration that do not exist yet — and pulses the title's artwork
     * in their place.
     */
    loading: Boolean = false,
    thumbnailPreview: (@Composable (seekFraction: Float) -> Unit)? = null,
    /** The frame nearest a chapter's start, for the TV chapter row. */
    chapterThumbnail: (@Composable (startMs: Long) -> Unit)? = null,
    sideGestures: SideGestureConfig? = null,
    chrome: PlayerChrome = PlayerChrome.Pointer,
    /**
     * The host has a layer of its own over the player — a channel list, a search panel —
     * which owns the D-pad for as long as it is up. The transport stays put underneath it
     * rather than timing out: it is what the viewer comes back to.
     */
    overlaid: Boolean = false,
    /**
     * RIGHT pressed with nothing ahead of the playhead. A live tune has the channel list
     * there; everything else leaves it null and the press stays a seek.
     */
    onSeekPastEnd: (() -> Unit)? = null,
    focusDecoration: ControlFocus = { _, _, _ -> Modifier },
    /**
     * A one-press action the host is offering right now — Skip Intro, Skip Credits. Drawn
     * over the picture whether or not the controls are up; apply the given modifier to the
     * button, which is how the overlay hands it the D-pad.
     */
    offer: (@Composable BoxScope.(focus: Modifier) -> Unit)? = null,
    extraControls: @Composable RowScope.() -> Unit = {},
    topEndControls: @Composable RowScope.() -> Unit = {},
) {
    var controlsVisible by remember { mutableStateOf(false) }
    var interactionTick by remember { mutableLongStateOf(0L) }
    var isSeeking by remember { mutableStateOf(false) }
    var seekFraction by remember { mutableFloatStateOf(0f) }
    var hoverFraction by remember { mutableStateOf<Float?>(null) }
    var menuOpen by remember { mutableIntStateOf(0) } // count of open menus
    // A TV opens menus as rows under the transport, not anchored popups, so which one is
    // open lives here, not inside the selector.
    var tvPicker by remember { mutableStateOf<TvPicker?>(null) }
    val anyMenuOpen = menuOpen > 0 || tvPicker != null
    val tv = chrome == PlayerChrome.Tv
    val dpadSeek = chrome != PlayerChrome.Touch
    fun toggleTvPicker(picker: TvPicker, open: Boolean) {
        tvPicker = if (open) picker else tvPicker.takeUnless { it == picker }
    }
    var overlayFocused by remember { mutableStateOf(false) }
    val offerFocus = remember { FocusRequester() }
    var offerFocused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val playPauseFocus = remember { FocusRequester() }
    val seekBarFocus = remember { FocusRequester() }
    var seekBarFocused by remember { mutableStateOf(false) }

    // rememberUpdatedState so pointerInput (keyed on Unit) always reads fresh values
    val positionNow by rememberUpdatedState(position)
    val durationMsState by rememberUpdatedState(durationMs)
    val onSeekState by rememberUpdatedState(onSeek)
    val onPlayPauseState by rememberUpdatedState(onPlayPause)
    val loadingState by rememberUpdatedState(loading)

    val topScrim = remember {
        Brush.verticalGradient(
            listOf(Color.Black.copy(alpha = 0.7f), Color.Black.copy(alpha = 0.3f), Color.Transparent),
        )
    }
    val bottomScrim = remember {
        Brush.verticalGradient(
            listOf(Color.Transparent, Color.Black.copy(alpha = 0.3f), Color.Black.copy(alpha = 0.7f)),
        )
    }
    val typography = MaterialTheme.typography
    val titleStyle = remember(typography) {
        typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp)
    }

    fun onInteraction() {
        controlsVisible = true
        interactionTick++
    }

    // A mouse reports a move per frame; bumping the tick on each one recomposed the overlay
    // and restarted its timers at display rate for as long as the pointer moved.
    val pointerThrottle = remember { Throttle(500.milliseconds) }
    fun onPointerActivity() {
        if (!controlsVisible || pointerThrottle.pass()) onInteraction()
    }

    // A stream that never finishes opening keeps every control away, and with them the
    // only way out on a touch screen: a tap brings back the bar with the back arrow alone.
    var loadingExitVisible by remember { mutableStateOf(false) }
    LaunchedEffect(loadingExitVisible, interactionTick) {
        if (loadingExitVisible) {
            delay(4000)
            loadingExitVisible = false
        }
    }
    if (!loading) loadingExitVisible = false

    @Composable
    fun TopBar(modifier: Modifier) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .background(topScrim)
                .padding(top = 16.dp, bottom = 32.dp)
                .windowInsetsPadding(playerChromeInsets),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // A TV exits with the remote's back key, which the host already
                // handles; the arrow was a D-pad stop leading nowhere.
                if (!tv) {
                    IconButton(
                        onClick = onBack,
                        modifier = focusDecoration("back", 999f, 1.08f),
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = colors.primary)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = titleStyle, color = colors.primary)
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.primaryDim)
                    }
                }
                topEndControls()
            }
        }
    }

    // Auto-hide — suppressed while any menu is open, and while the host has a layer of its
    // own over the player. Timing out under one disposes the control that layer was opened
    // from, so closing it restored focus to a node that no longer existed and left the
    // window with nothing focused at all. A TV gets five seconds, the Media3 default: every
    // remote press restarts the clock, so a longer window only leaves chrome on the picture.
    LaunchedEffect(interactionTick, isPlaying, anyMenuOpen, overlaid) {
        if (isPlaying && !anyMenuOpen && !overlaid) {
            delay(if (tv) 5000 else 4000)
            controlsVisible = false
        }
    }

    // A headset, the notification or the lock screen pauses through the media session,
    // and the picture stopped with nothing on it to resume from.
    LaunchedEffect(paused) {
        if (paused) onInteraction()
    }

    var skipBurst by remember { mutableStateOf<SkipBurst?>(null) }
    LaunchedEffect(skipBurst) {
        if (skipBurst != null) {
            delay(SkipBurstWindowMs)
            skipBurst = null
        }
    }
    // Stacks from where the burst began: the player reports a seek's new position late,
    // so reading the playhead on every tap landed a fast burst on the same spot.
    fun skipTaps(forward: Boolean, taps: Int) {
        val burst = skipBurst?.takeIf { it.forward == forward }
            ?: SkipBurst(forward, anchorMs = positionNow.value, taps = 0)
        val next = burst.copy(taps = burst.taps + taps)
        skipBurst = next
        onSeekState(next.targetMs.coerceIn(0, durationMsState))
    }

    // Request focus so keyboard events (space, arrows) work on desktop
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // The bar is where a remote spends the watch: LEFT and RIGHT are step back and step
    // forward the moment the controls appear, which is what every TV player does. Landing
    // on play/pause instead left the bar reachable only by a viewer who knew it was
    // there. A tune with no bar at all — raw live, nothing to seek in — still opens on
    // play/pause.
    val seekBarTakesDpad = dpadSeek && !isLive && (durationMs > 0 || onSeekPastEnd != null)

    // The controls leave composition when they auto-hide, taking whatever held focus with
    // them, and the fade back in restores no one — a remote was left pressing keys at a
    // player that had stopped listening. Park focus on the root while they are away and
    // hand it back when they return.
    // An offer — Skip Credits — takes that parked focus instead, so OK on the picture is
    // the skip the viewer is looking at. It composes on the frame it appears, so the request
    // is repeated until it lands, the same way the controls' entry is.
    val offered = offer != null && !loading
    LaunchedEffect(controlsVisible, loading, seekBarTakesDpad, offered) {
        if (!controlsVisible || loading) {
            if (offered) {
                repeat(EntryFocusFrames) {
                    runCatching { offerFocus.requestFocus() }
                    withFrameNanos { }
                    if (offerFocused) return@LaunchedEffect
                }
            }
            runCatching { focusRequester.requestFocus() }
            return@LaunchedEffect
        }
        // The controls are inside an AnimatedVisibility that has not composed them yet on
        // the frame the flag flips, and a request issued before the target is laid out is
        // dropped without complaint — the platform then hands initial focus to whatever
        // came first, which is play/pause. So the request is repeated until the bar
        // reports that it actually took it.
        repeat(EntryFocusFrames) {
            if (!seekBarTakesDpad) {
                if (runCatching { playPauseFocus.requestFocus() }.isSuccess) return@LaunchedEffect
                withFrameNanos { }
                return@repeat
            }
            runCatching { seekBarFocus.requestFocus() }
            withFrameNanos { }
            if (seekBarFocused) return@LaunchedEffect
        }
        if (seekBarTakesDpad && !seekBarFocused) runCatching { playPauseFocus.requestFocus() }
    }

    // Side gesture state (brightness/volume drag)
    val sideGesturesState by rememberUpdatedState(sideGestures)
    var draggedSide by remember { mutableStateOf<DragSide?>(null) }
    // Volume detent at 100%: drag pauses there so it's easy to land on a safe
    // max (boost can distort action scenes). User can push past with extra drag.
    var volumeDetentLocked by remember { mutableStateOf(false) }
    var volumeDetentExcess by remember { mutableFloatStateOf(0f) }
    var hintVisible by remember { mutableStateOf(false) }
    LaunchedEffect(sideGestures?.showHint) {
        if (sideGestures?.showHint == true) {
            hintVisible = true
            delay(1500)
            hintVisible = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .onFocusChanged { overlayFocused = it.isFocused }
            .focusable()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { offset ->
                        if (loadingState) {
                            loadingExitVisible = !loadingExitVisible
                            interactionTick++
                            return@detectTapGestures
                        }
                        if (skipBurst != null) {
                            skipTaps(forward = offset.x > size.width / 2, taps = 1)
                            onInteraction()
                            return@detectTapGestures
                        }
                        // A click on the picture is play/pause wherever there is a mouse;
                        // a finger has no hover, so its tap is what brings the controls up.
                        if (chrome == PlayerChrome.Pointer) {
                            onPlayPauseState()
                            onInteraction()
                        } else if (controlsVisible) {
                            controlsVisible = false
                        } else {
                            onInteraction()
                        }
                        focusRequester.requestFocus()
                    },
                    onDoubleTap = { offset ->
                        val forward = offset.x > size.width / 2
                        val bursting = skipBurst?.forward == forward
                        skipTaps(forward, taps = if (bursting) 2 else 1)
                        onInteraction()
                    },
                )
            }
            // Show controls on mouse hover (desktop). Skip touch — finger-down
            // produces Move samples that would fight detectTapGestures and cause
            // a flash-then-hide on first tap.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Move &&
                            event.changes.any { it.type == PointerType.Mouse }
                        ) {
                            onPointerActivity()
                        }
                    }
                }
            }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                onInteraction()
                // Arrows only seek while the surface itself holds focus. Consuming them
                // unconditionally here would swallow every D-pad move a control below
                // needs to hand focus to its neighbour.
                when {
                    event.key == Key.Spacebar -> { onPlayPause(); true }
                    event.key == Key.Escape || event.key == Key.Back -> {
                        // The first back on a TV closes the row the transport has open, not
                        // the player. Touch dropdowns keep their open state inside the
                        // component, so a close could not reach them from here — those pass
                        // through to the host as before.
                        if (tvPicker != null) {
                            tvPicker = null
                            true
                        } else {
                            onBack(); true
                        }
                    }
                    // The offer stands in for the picture while it holds the parked focus:
                    // its own OK is the skip, and the arrows still seek.
                    !overlayFocused && !offerFocused -> false
                    event.key == Key.Enter -> { onPlayPause(); true }
                    event.key == Key.DirectionLeft -> { onSeekState((positionNow.value - 10_000).coerceAtLeast(0)); true }
                    event.key == Key.DirectionRight -> {
                        val past = onSeekPastEnd
                        // Same rule as the bar's: with nothing ahead of the playhead the
                        // press is spent on whatever the host put there instead.
                        if (past != null && positionNow.value >= durationMsState - PastEndSlackMs) past()
                        else onSeekState((positionNow.value + 10_000).coerceAtMost(durationMsState))
                        true
                    }
                    else -> false
                }
            },
    ) {
        // Inset drag region for brightness/volume gestures. Excludes:
        // - bottom: home-swipe / PiP gesture + seek bar
        // - top: title scrim + back button
        // - sides: Android back-edge gesture
        if (sideGestures != null) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(top = 80.dp, bottom = 120.dp, start = 32.dp, end = 32.dp)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragStart = { offset ->
                                if (sideGesturesState == null) return@detectVerticalDragGestures
                                draggedSide = if (offset.x > size.width / 2) DragSide.Right else DragSide.Left
                            },
                            onDragEnd = {
                                draggedSide = null
                                volumeDetentLocked = false
                                volumeDetentExcess = 0f
                            },
                            onDragCancel = {
                                draggedSide = null
                                volumeDetentLocked = false
                                volumeDetentExcess = 0f
                            },
                            onVerticalDrag = { _, dragAmount ->
                                val cfg = sideGesturesState ?: return@detectVerticalDragGestures
                                val side = draggedSide ?: return@detectVerticalDragGestures
                                // Full inner-region height = full range
                                val frac = -dragAmount / size.height.toFloat()
                                when (side) {
                                    DragSide.Left -> cfg.onBrightnessChange((cfg.brightness + frac).coerceIn(0f, 1f))
                                    DragSide.Right -> {
                                        val delta = frac * cfg.maxVolume
                                        // Resistance band around 100%: ~4% of full
                                        // range of extra drag required to push past.
                                        val resistance = 0.04f * cfg.maxVolume
                                        if (volumeDetentLocked) {
                                            volumeDetentExcess += delta
                                            when {
                                                volumeDetentExcess > resistance -> {
                                                    val released = 1f + (volumeDetentExcess - resistance)
                                                    volumeDetentLocked = false
                                                    volumeDetentExcess = 0f
                                                    cfg.onVolumeChange(released.coerceIn(0f, cfg.maxVolume))
                                                }
                                                volumeDetentExcess < -resistance -> {
                                                    val released = 1f + (volumeDetentExcess + resistance)
                                                    volumeDetentLocked = false
                                                    volumeDetentExcess = 0f
                                                    cfg.onVolumeChange(released.coerceIn(0f, cfg.maxVolume))
                                                }
                                                // else: hold at 100%
                                            }
                                        } else {
                                            val target = cfg.volume + delta
                                            val crosses = (cfg.volume < 1f && target >= 1f) ||
                                                (cfg.volume > 1f && target <= 1f)
                                            if (crosses && cfg.maxVolume > 1f) {
                                                volumeDetentLocked = true
                                                volumeDetentExcess = target - 1f
                                                cfg.onVolumeChange(1f)
                                            } else {
                                                cfg.onVolumeChange(target.coerceIn(0f, cfg.maxVolume))
                                            }
                                        }
                                    }
                                }
                            },
                        )
                    },
            )
        }

        // Brightness/volume drag indicators — visible during drag and during the
        // discoverability flash window.
        sideGestures?.let { cfg ->
            val showLeft = draggedSide == DragSide.Left || hintVisible
            val showRight = draggedSide == DragSide.Right || hintVisible
            AnimatedVisibility(
                visible = showLeft,
                enter = fadeIn(tween(120)),
                exit = fadeOut(tween(250)),
                modifier = Modifier.align(Alignment.CenterStart),
            ) {
                SideIndicator(
                    icon = Icons.Outlined.BrightnessHigh,
                    fraction = cfg.brightness,
                    label = "${(cfg.brightness * 100).toInt()}%",
                    colors = colors,
                )
            }
            AnimatedVisibility(
                visible = showRight,
                enter = fadeIn(tween(120)),
                exit = fadeOut(tween(250)),
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                SideIndicator(
                    icon = if (cfg.volume <= 0.001f) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp,
                    fraction = (cfg.volume / cfg.maxVolume).coerceIn(0f, 1f),
                    label = "${(cfg.volume * 100).toInt()}%",
                    colors = colors,
                )
            }
        }

        skipBurst?.let { burst ->
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.4f)
                    .align(if (burst.forward) Alignment.CenterEnd else Alignment.CenterStart)
                    .background(Color.White.copy(alpha = 0.08f), if (burst.forward) RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50) else RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        if (burst.forward) Icons.Rounded.FastForward else Icons.Rounded.FastRewind,
                        contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp),
                    )
                    Text("${burst.seconds}s", style = MaterialTheme.typography.labelMedium, color = Color.White)
                }
            }
        }
        AnimatedVisibility(
            visible = controlsVisible && !loading,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.fillMaxSize(),
        ) {
            val shownMs by position.collectAsState()
            val currentMs = { shownMs }
            Box(Modifier.fillMaxSize()) {
                // Center transport controls (YouTube-style). Composed ahead of the rest
                // of the chrome, and play/pause ahead of its own row, because entry focus
                // goes to the first control that registers a focus key.
                //
                // A TV with a seek bar does not get these: the bar is the transport, and
                // the disc sat over the picture burning a focus ring the remote spent on
                // nothing new. Live keeps them — with no bar, they are the transport. A
                // mouse has play/pause beside the bar and the picture itself to click.
                if (chrome == PlayerChrome.Touch || (tv && isLive)) {
                    Box(modifier = Modifier.align(Alignment.Center), contentAlignment = Alignment.Center) {
                        IconButton(
                            onClick = { onPlayPause(); onInteraction() },
                            modifier = Modifier
                                .size(88.dp)
                                .focusRequester(playPauseFocus)
                                .then(focusDecoration("playpause", 999f, 1.06f))
                                .border(3.dp, Color.White.copy(alpha = 0.94f), CircleShape)
                                .background(colors.controlBackground, CircleShape),
                        ) {
                            Icon(
                                if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                "Play/Pause", tint = colors.primary, modifier = Modifier.size(56.dp),
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(40.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(
                                onClick = { onSeek((currentMs() - 10_000).coerceAtLeast(0)); onInteraction() },
                                modifier = Modifier.size(56.dp).then(focusDecoration("rewind", 999f, 1.08f)),
                            ) {
                                Icon(Icons.Rounded.FastRewind, "Rewind 10s", tint = colors.primary, modifier = Modifier.size(36.dp))
                            }

                            Spacer(Modifier.width(88.dp))

                            IconButton(
                                onClick = { onSeek((currentMs() + 10_000).coerceAtMost(durationMs)); onInteraction() },
                                modifier = Modifier.size(56.dp).then(focusDecoration("forward", 999f, 1.08f)),
                            ) {
                                Icon(Icons.Rounded.FastForward, "Forward 10s", tint = colors.primary, modifier = Modifier.size(36.dp))
                            }
                        }
                    }
                }

                TopBar(Modifier.align(Alignment.TopCenter))

                // Bottom scrim — edge-to-edge, controls inset
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(bottomScrim)
                        .windowInsetsPadding(playerChromeInsets)
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 12.dp, top = 32.dp),
                ) {
                    val displayMs = { if (isSeeking) (seekFraction * durationMs).toLong() else currentMs() }

                    if (isLive) {
                        // Live mode: show LIVE badge instead of seek bar
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (chrome == PlayerChrome.Pointer) {
                                BarPlayPause(isPlaying, colors, playPauseFocus, focusDecoration) { onPlayPause(); onInteraction() }
                                Spacer(Modifier.width(8.dp))
                            }
                            Box(
                                modifier = Modifier
                                    .background(Color.Red, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    "LIVE",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White,
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            extraControls()
                        }
                    } else {
                        // Thumbnail preview above seek bar — shown while scrubbing
                        // (drag) or hovering (mouse). seekFraction wins so the live
                        // drag position is reflected even if the cursor lags.
                        val previewFraction = when {
                            isSeeking -> seekFraction
                            hoverFraction != null -> hoverFraction
                            else -> null
                        }
                        if (previewFraction != null && thumbnailPreview != null) {
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.TopCenter,
                            ) {
                                thumbnailPreview(previewFraction)
                            }
                        }

                        PositionSeekBar(
                            progress = {
                                if (isSeeking) seekFraction
                                else if (durationMs > 0) currentMs().toFloat() / durationMs.toFloat()
                                else 0f
                            },
                            onSeek = { fraction ->
                                isSeeking = false
                                onSeek((fraction * durationMs).toLong())
                            },
                            onSeeking = { fraction ->
                                isSeeking = true
                                seekFraction = fraction
                                onInteraction()
                            },
                            onHover = { fraction ->
                                hoverFraction = fraction
                                if (fraction != null) onPointerActivity()
                            },
                            chapterMarkers = chapterMarkers,
                            regions = regions,
                            colors = colors,
                            focusDecoration = Modifier
                                .focusRequester(seekBarFocus)
                                .onFocusChanged { seekBarFocused = it.isFocused }
                                .then(focusDecoration("seek", 14f, 1f)),
                            dpadStepFraction = if (dpadSeek && durationMs > 0) 10_000f / durationMs else 0f,
                            onPastEnd = onSeekPastEnd,
                            // A remote lands on the bar, so the bar answers its middle button.
                            onActivate = onPlayPauseState.takeIf { dpadSeek },
                        )

                        Spacer(Modifier.height(4.dp))
                    }

                    if (!isLive) Row(
                        // Down from the bar returns to the control last used, not to
                        // whichever one happens to sit under the playhead.
                        modifier = Modifier.fillMaxWidth().focusRestorer().focusGroup(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // On a TV the time is the one number a viewer reads, so it gets the
                        // state glyph and the larger type; the disc that used to carry it
                        // is gone from the picture.
                        if (chrome == PlayerChrome.Pointer) {
                            BarPlayPause(isPlaying, colors, playPauseFocus, focusDecoration) { onPlayPause(); onInteraction() }
                            Spacer(Modifier.width(8.dp))
                        }
                        if (tv) TvStatusLabel(isPlaying, displayMs, durationMs, colors) else PositionLabel(displayMs, durationMs, colors)
                        Spacer(Modifier.width(12.dp))
                        extraControls()

                        // Chapter list button
                        if (chapters.isNotEmpty()) {
                            ChapterListButton(
                                chapters = chapters,
                                currentMs = displayMs,
                                colors = colors,
                                onSelectChapter = { chapter ->
                                    onSeek(chapter.startMs)
                                    onInteraction()
                                },
                                onMenuToggle = if (tv) { open -> toggleTvPicker(TvPicker.Chapters, open) }
                                else { open -> menuOpen += if (open) 1 else -1 },
                                focusDecoration = focusDecoration,
                                tv = tv,
                                expanded = tvPicker == TvPicker.Chapters,
                            )
                        }

                        Spacer(Modifier.weight(1f))

                        SpeedSelector(currentSpeed = playbackSpeed, colors = colors, onSelect = { onSetPlaybackSpeed(it); onInteraction() }, onMenuToggle = { open -> menuOpen += if (open) 1 else -1 }, focusDecoration = focusDecoration, tv = tv)

                        if (audioTracks.size > 1) {
                            TrackSelector(
                                icon = Icons.Outlined.Audiotrack, label = "Audio",
                                tracks = audioTracks, selectedTrack = selectedAudioTrack,
                                allowOff = false, colors = colors,
                                onSelect = { onSelectAudioTrack(it); onInteraction() },
                                onMenuToggle = if (tv) { open -> toggleTvPicker(TvPicker.Audio, open) }
                                else { open -> menuOpen += if (open) 1 else -1 },
                                focusDecoration = focusDecoration, focusKeyPrefix = "audio",
                                tv = tv,
                                expanded = tvPicker == TvPicker.Audio,
                            )
                        }

                        if (subtitleTracks.isNotEmpty() || onFindSubtitles != null) {
                            TrackSelector(
                                icon = Icons.Outlined.Subtitles, label = "Subtitles",
                                tracks = subtitleTracks, selectedTrack = selectedSubtitleTrack,
                                allowOff = true, colors = colors,
                                onSelect = { onSelectSubtitleTrack(it); onInteraction() },
                                onMenuToggle = if (tv) { open -> toggleTvPicker(TvPicker.Subtitles, open) }
                                else { open -> menuOpen += if (open) 1 else -1 },
                                focusDecoration = focusDecoration, focusKeyPrefix = "subtitles",
                                extraLabel = "Find subtitles…".takeIf { onFindSubtitles != null },
                                onExtra = { onFindSubtitles?.invoke(); onInteraction() },
                                tv = tv,
                                expanded = tvPicker == TvPicker.Subtitles,
                            )
                        }

                        AspectRatioSelector(currentRatio = aspectRatio, colors = colors, onSelect = { onSetAspectRatio(it); onInteraction() }, focusDecoration = focusDecoration, tv = tv)

                        if (onToggleFullscreen != null) {
                            IconButton(
                                onClick = { onToggleFullscreen(); onInteraction() },
                                modifier = Modifier.size(36.dp).then(focusDecoration("fullscreen", 999f, 1.08f)),
                            ) {
                                Icon(
                                    if (isFullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                                    contentDescription = if (isFullscreen) "Exit fullscreen" else "Fullscreen",
                                    tint = colors.primary, modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                    }

                    when (tvPicker.takeIf { tv }) {
                        TvPicker.Chapters -> ChapterPicker(
                            chapters = chapters,
                            currentMs = displayMs,
                            thumbnail = chapterThumbnail,
                            colors = colors,
                            focusDecoration = focusDecoration,
                            onSelect = { chapter ->
                                onSeek(chapter.startMs)
                                tvPicker = null
                                onInteraction()
                            },
                        )
                        TvPicker.Subtitles -> TrackPicker(
                            title = "Subtitles",
                            tracks = subtitleTracks,
                            selected = selectedSubtitleTrack,
                            allowOff = true,
                            colors = colors,
                            focusKeyPrefix = "subtitles",
                            focusDecoration = focusDecoration,
                            onFind = onFindSubtitles,
                            onSelect = { track ->
                                onSelectSubtitleTrack(track)
                                tvPicker = null
                                onInteraction()
                            },
                            onFound = { tvPicker = null; onInteraction() },
                        )
                        TvPicker.Audio -> TrackPicker(
                            title = "Audio",
                            tracks = audioTracks,
                            selected = selectedAudioTrack,
                            allowOff = false,
                            colors = colors,
                            focusKeyPrefix = "audio",
                            focusDecoration = focusDecoration,
                            onFind = null,
                            onSelect = { track ->
                                onSelectAudioTrack(track)
                                tvPicker = null
                                onInteraction()
                            },
                            onFound = {},
                        )
                        null -> Unit
                    }
                }
            }
        }

        // Branding overlay — fades out once playback has begun
        //
        // "Has begun", not "is playing": this instance is composed fresh whenever the
        // player comes back from the background or out of PiP, and a paused title has
        // never reported playing to it. Keyed on isPlaying alone, the logo was redrawn
        // over the transport of something twenty minutes in, and the only way out was to
        // press the play button underneath it. A playhead that has moved is the durable
        // fact, so it seeds the state; the flag then latches on the live signal.
        var hasStartedPlaying by remember { mutableStateOf(playbackHasBegun(isPlaying, position.value)) }
        if (isPlaying) hasStartedPlaying = true
        BrandingOverlay(
            visible = !hasStartedPlaying,
            breathing = loading && !hasStartedPlaying,
            logoUrl = logoUrl,
            title = title,
            colors = colors,
        )

        AnimatedVisibility(
            visible = loading && loadingExitVisible,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize()) { TopBar(Modifier.align(Alignment.TopCenter)) }
        }

        if (offered) {
            offer?.invoke(this, Modifier.focusRequester(offerFocus).onFocusChanged { offerFocused = it.hasFocus })
        }
    }
}

/**
 * Has this title played at all yet?
 *
 * The playhead is the part that survives the player being torn down and rebuilt, which is
 * what a background/foreground round trip does to it.
 */
internal fun playbackHasBegun(isPlaying: Boolean, positionMs: Long): Boolean =
    isPlaying || positionMs > 0

/**
 * A slow breath for the title artwork while a stream opens.
 *
 * Only started when it is wanted: an infinite animation keeps the composition busy for
 * as long as it exists, which a still player has no reason to pay and a UI test can
 * never idle past.
 */
@Composable
private fun brandingBreath(active: Boolean): State<Float> {
    if (!active) return remember { mutableFloatStateOf(1f) }
    val transition = rememberInfiniteTransition(label = "branding")
    return transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "brandingBreath",
    )
}

// Every animated value here is read in the draw phase: the breath runs at display rate for
// the whole time a stream opens, and a read in composition re-ran the overlay each frame.
@Composable
private fun BrandingOverlay(
    visible: Boolean,
    breathing: Boolean,
    logoUrl: String?,
    title: String,
    colors: PlayerColors,
) {
    val brandingAlpha = animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 800),
    )
    val breath = brandingBreath(breathing)
    val shown by remember { derivedStateOf { brandingAlpha.value > 0f } }
    if (!shown) return
    Box(
        modifier = Modifier.fillMaxSize()
            .drawBehind { drawRect(Color.Black.copy(alpha = 0.6f * brandingAlpha.value)) }
            .graphicsLayer { alpha = brandingAlpha.value * breath.value },
        contentAlignment = Alignment.Center,
    ) {
        // The title held the space while the logo loaded and faded out under it, so it
        // flashed up on every start; it now only stands in for a logo there isn't.
        var load by remember(logoUrl) { mutableStateOf(LogoLoad.Loading) }
        val shows = branding(logoUrl, load)
        if (logoUrl != null && load != LogoLoad.Failed) {
            AsyncImage(
                model = logoUrl, contentDescription = title,
                contentScale = ContentScale.Fit,
                onState = {
                    load = when (it) {
                        is AsyncImagePainter.State.Success -> LogoLoad.Loaded
                        is AsyncImagePainter.State.Error -> LogoLoad.Failed
                        else -> LogoLoad.Loading
                    }
                },
                modifier = Modifier.widthIn(max = 320.dp).heightIn(max = 120.dp),
            )
        }
        if (shows == Branding.Title) {
            Text(
                title,
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                ),
                color = colors.primary, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 48.dp),
            )
        }
    }
}

internal enum class LogoLoad { Loading, Loaded, Failed }

internal enum class Branding { Nothing, Logo, Title }

internal fun branding(logoUrl: String?, load: LogoLoad): Branding = when {
    logoUrl == null || load == LogoLoad.Failed -> Branding.Title
    load == LogoLoad.Loaded -> Branding.Logo
    else -> Branding.Nothing
}

// -- Helper composables --

// The two leaves below exist so the ~4Hz position tick invalidates only them and never the
// surrounding chrome (scrims, selectors, focus decorations).

@Composable
private fun PositionSeekBar(
    progress: () -> Float,
    onSeek: (Float) -> Unit,
    onSeeking: (Float) -> Unit,
    onHover: (Float?) -> Unit,
    chapterMarkers: List<Float>,
    regions: List<SeekBarRegion>,
    colors: PlayerColors,
    focusDecoration: Modifier,
    dpadStepFraction: Float,
    onPastEnd: (() -> Unit)?,
    onActivate: (() -> Unit)?,
) {
    MediaSeekBar(
        progress = progress,
        onSeek = onSeek,
        onSeeking = onSeeking,
        onHover = onHover,
        chapterMarkers = chapterMarkers,
        regions = regions,
        activeColor = colors.seekBarActive,
        inactiveColor = colors.seekBarInactive,
        modifier = Modifier.fillMaxWidth(),
        focusDecoration = focusDecoration,
        dpadStepFraction = dpadStepFraction,
        onPastEnd = onPastEnd,
        onActivate = onActivate,
    )
}

@Composable
private fun PositionLabel(currentMs: () -> Long, durationMs: Long, colors: PlayerColors) {
    val typography = MaterialTheme.typography
    val style = remember(typography) {
        typography.labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp)
    }
    val total = remember(durationMs) { formatTime(durationMs) }
    Text("${formatTime(currentMs())} / $total", style = style, color = colors.primaryDim)
}

/**
 * The TV's stand-in for the center disc: the state glyph and the time, in the type a
 * viewer reads from the sofa. It is not a control — the bar is — so it takes no focus.
 */
@Composable
private fun TvStatusLabel(isPlaying: Boolean, currentMs: () -> Long, durationMs: Long, colors: PlayerColors) {
    val typography = MaterialTheme.typography
    val style = remember(typography) { typography.titleMedium.copy(fontWeight = FontWeight.Medium) }
    val total = remember(durationMs) { formatTime(durationMs) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = if (isPlaying) "Playing" else "Paused",
            tint = colors.primary, modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text("${formatTime(currentMs())} / $total", style = style, color = colors.primaryDim)
    }
}

@Composable
private fun BarPlayPause(
    isPlaying: Boolean,
    colors: PlayerColors,
    focus: FocusRequester,
    focusDecoration: ControlFocus,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(40.dp).focusRequester(focus).then(focusDecoration("playpause", 999f, 1.08f)),
    ) {
        Icon(
            if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            "Play/Pause", tint = colors.primary, modifier = Modifier.size(28.dp),
        )
    }
}

@Composable
private fun ChapterPicker(
    chapters: List<OverlayChapter>,
    currentMs: () -> Long,
    thumbnail: (@Composable (startMs: Long) -> Unit)?,
    colors: PlayerColors,
    focusDecoration: ControlFocus,
    onSelect: (OverlayChapter) -> Unit,
) {
    val ordered = remember(chapters) { chapters.filterNot { it.detected } + chapters.filter { it.detected } }
    val position by rememberUpdatedState(currentMs)
    val currentIdx by remember(ordered) {
        derivedStateOf { ordered.indexOfLast { !it.detected && it.startMs <= position() } }
    }
    TvPickerRow(
        title = "Chapters",
        items = ordered.mapIndexed { idx, chapter ->
            TvPickerItem(
                label = chapter.title,
                detail = if (chapter.detected) {
                    "${formatTime(chapter.startMs)}-${formatTime(chapter.endMs ?: chapter.startMs)} (detected)"
                } else {
                    formatTime(chapter.startMs)
                },
                selected = idx == currentIdx,
                frame = thumbnail?.let { { it(chapter.startMs) } },
            )
        },
        colors = colors,
        focusKeyPrefix = "chapter",
        focusDecoration = focusDecoration,
        onSelect = { onSelect(ordered[it]) },
    )
}

@Composable
private fun TrackPicker(
    title: String,
    tracks: List<TrackInfo>,
    selected: TrackInfo?,
    allowOff: Boolean,
    colors: PlayerColors,
    focusKeyPrefix: String,
    focusDecoration: ControlFocus,
    onFind: (() -> Unit)?,
    onSelect: (TrackInfo?) -> Unit,
    onFound: () -> Unit,
) {
    val options: List<Pair<TvPickerItem, () -> Unit>> = buildList {
        if (onFind != null) add(TvPickerItem("Find subtitles…", leadingIcon = Icons.Outlined.Search) to { onFind(); onFound() })
        if (allowOff) add(TvPickerItem("Off", selected = selected == null) to { onSelect(null) })
        tracks.forEach { track ->
            add(TvPickerItem(track.label, selected = selected?.id == track.id) to { onSelect(track) })
        }
    }
    TvPickerRow(
        title = title,
        items = options.map { it.first },
        colors = colors,
        focusKeyPrefix = focusKeyPrefix,
        focusDecoration = focusDecoration,
        onSelect = { options[it].second() },
    )
}

@Composable
internal fun TrackSelector(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tracks: List<TrackInfo>,
    selectedTrack: TrackInfo?,
    allowOff: Boolean,
    colors: PlayerColors,
    onSelect: (TrackInfo?) -> Unit,
    onMenuToggle: (Boolean) -> Unit = {},
    focusDecoration: ControlFocus = { _, _, _ -> Modifier },
    focusKeyPrefix: String = "track",
    extraLabel: String? = null,
    onExtra: () -> Unit = {},
    tv: Boolean = false,
    expanded: Boolean = false,
) {
    var expandedLocal by remember { mutableStateOf(false) }
    val isOpen = if (tv) expanded else expandedLocal
    // When the TV row closes, focus goes back to the button that opened it: the viewer
    // was just talking to this control, and the row is gone.
    val triggerFocus = remember { FocusRequester() }
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(isOpen) {
        if (wasOpen && !isOpen) triggerFocus.requestFocus()
        wasOpen = isOpen
    }
    // primaryFaint is the tint of a control that cannot be used, and subtitles being off
    // is not that — the button is exactly as live as it is with a track selected. Follow
    // the aspect and speed controls instead: accent for a setting turned on, primaryDim
    // for one left alone. An audio selector always has a track, so it signals nothing.
    val tint = when {
        !allowOff -> colors.primary
        selectedTrack != null -> colors.accent
        else -> colors.primaryDim
    }
    Box {
        IconButton(
            onClick = {
                if (tv) onMenuToggle(!isOpen)
                else { expandedLocal = true; onMenuToggle(true) }
            },
            modifier = Modifier.size(36.dp).focusRequester(triggerFocus).then(focusDecoration(focusKeyPrefix, 999f, 1.08f)),
        ) {
            Icon(icon, label, tint = tint, modifier = Modifier.size(20.dp))
        }
        if (!tv) DropdownMenu(
            expanded = expandedLocal, onDismissRequest = { expandedLocal = false; onMenuToggle(false) },
            offset = DpOffset(0.dp, (-48).dp), shape = RoundedCornerShape(8.dp),
            containerColor = colors.menuBackground,
            modifier = Modifier.padding(horizontal = MenuFocusGutter),
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                color = colors.primaryFaint, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            // Above the tracks, not below them: a file with twenty embedded subtitle
            // tracks pushes anything after the list off the end of a scrolling menu.
            if (extraLabel != null) {
                TrackMenuItem(
                    label = extraLabel, selected = false, colors = colors,
                    onClick = { onExtra(); expandedLocal = false; onMenuToggle(false) },
                    focusModifier = focusDecoration("$focusKeyPrefix.extra", 8f, 1f),
                    leadingIcon = Icons.Outlined.Search,
                )
                if (allowOff || tracks.isNotEmpty()) {
                    HorizontalDivider(color = colors.primaryFaint.copy(alpha = 0.25f))
                }
            }
            if (allowOff) {
                TrackMenuItem(
                    label = "Off", selected = selectedTrack == null, colors = colors,
                    onClick = { onSelect(null); expandedLocal = false; onMenuToggle(false) },
                    focusModifier = focusDecoration("$focusKeyPrefix.off", 8f, 1f),
                )
            }
            tracks.forEachIndexed { idx, track ->
                TrackMenuItem(
                    label = track.label, selected = selectedTrack?.id == track.id, colors = colors,
                    onClick = { onSelect(track); expandedLocal = false; onMenuToggle(false) },
                    focusModifier = focusDecoration("$focusKeyPrefix.$idx", 8f, 1f),
                )
            }
        }
    }
}

@Composable
internal fun SpeedSelector(
    currentSpeed: Float,
    colors: PlayerColors,
    onSelect: (Float) -> Unit,
    onMenuToggle: (Boolean) -> Unit = {},
    focusDecoration: ControlFocus = { _, _, _ -> Modifier },
    tv: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    val isNonDefault = currentSpeed != 1.0f
    Box {
        TextButton(
            onClick = {
                if (tv) {
                    // One stop, no popup: the remote's OK walks the value forward and
                    // wraps. The label on the button is the value, so the viewer sees
                    // what the press did.
                    val idx = speeds.indexOfFirst { it == currentSpeed }
                    onSelect(speeds[(idx + 1) % speeds.size])
                } else {
                    expanded = true; onMenuToggle(true)
                }
            },
            colors = ButtonDefaults.textButtonColors(contentColor = if (isNonDefault) colors.accent else colors.primaryDim),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            modifier = Modifier.height(36.dp).then(focusDecoration("speed", 999f, 1.03f)),
        ) {
            Text(
                if (currentSpeed == 1.0f) "1x" else "${currentSpeed}x",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (isNonDefault) FontWeight.Bold else FontWeight.Medium),
            )
        }
        if (!tv) DropdownMenu(
            expanded = expanded, onDismissRequest = { expanded = false; onMenuToggle(false) },
            offset = DpOffset(0.dp, (-48).dp), shape = RoundedCornerShape(8.dp),
            containerColor = colors.menuBackground,
            modifier = Modifier.padding(horizontal = MenuFocusGutter),
        ) {
            Text("SPEED", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                color = colors.primaryFaint, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            speeds.forEach { speed ->
                TrackMenuItem(
                    label = "${speed}x", selected = currentSpeed == speed, colors = colors,
                    onClick = { onSelect(speed); expanded = false; onMenuToggle(false) },
                    focusModifier = focusDecoration("speed.$speed", 8f, 1f),
                )
            }
        }
    }
}

@Composable
internal fun AspectRatioSelector(
    currentRatio: AspectRatio,
    colors: PlayerColors,
    onSelect: (AspectRatio) -> Unit,
    focusDecoration: ControlFocus = { _, _, _ -> Modifier },
    tv: Boolean = false,
) {
    val nextRatio = when (currentRatio) { AspectRatio.FIT -> AspectRatio.CROP; AspectRatio.CROP -> AspectRatio.STRETCH; AspectRatio.STRETCH -> AspectRatio.FIT }
    val icon = when (currentRatio) { AspectRatio.FIT -> Icons.Outlined.FitScreen; AspectRatio.CROP -> Icons.Outlined.Crop; AspectRatio.STRETCH -> Icons.Outlined.AspectRatio }
    val label = when (currentRatio) { AspectRatio.FIT -> "Fit"; AspectRatio.CROP -> "Crop"; AspectRatio.STRETCH -> "Stretch" }
    val tint = if (currentRatio != AspectRatio.FIT) colors.accent else colors.primaryDim
    if (tv) {
        // A bare 20dp icon names its setting to no one at arm's length; the word is the
        // control on a TV.
        TextButton(
            onClick = { onSelect(nextRatio) },
            colors = ButtonDefaults.textButtonColors(contentColor = tint),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            modifier = Modifier.height(36.dp).then(focusDecoration("aspect", 999f, 1.03f)),
        ) {
            Icon(icon, label, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = tint)
        }
    } else {
        IconButton(
            onClick = { onSelect(nextRatio) },
            modifier = Modifier.size(36.dp).then(focusDecoration("aspect", 999f, 1.08f)),
        ) {
            Icon(icon, label, tint = tint, modifier = Modifier.size(20.dp))
        }
    }
}


@Composable
private fun TrackMenuItem(
    label: String,
    selected: Boolean,
    colors: PlayerColors,
    onClick: () -> Unit,
    focusModifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
) {
    DropdownMenuItem(
        text = { Text(label, color = if (selected) colors.accent else colors.primaryDim, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, style = MaterialTheme.typography.bodyMedium) },
        onClick = onClick,
        leadingIcon = when {
            selected -> { { Icon(Icons.Outlined.Check, "Selected", tint = colors.accent, modifier = Modifier.size(16.dp)) } }
            leadingIcon != null -> { { Icon(leadingIcon, null, tint = colors.primaryFaint, modifier = Modifier.size(16.dp)) } }
            else -> null
        },
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
        modifier = Modifier.height(40.dp).then(focusModifier),
    )
}

/** Format milliseconds to "h:mm:ss" or "m:ss". */
fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    else "$minutes:${seconds.toString().padStart(2, '0')}"
}

@Composable
internal fun ChapterListButton(
    chapters: List<OverlayChapter>,
    currentMs: () -> Long,
    colors: PlayerColors,
    onSelectChapter: (OverlayChapter) -> Unit,
    onMenuToggle: (Boolean) -> Unit = {},
    focusDecoration: ControlFocus = { _, _, _ -> Modifier },
    tv: Boolean = false,
    expanded: Boolean = false,
) {
    var expandedLocal by remember { mutableStateOf(false) }
    val isOpen = if (tv) expanded else expandedLocal
    // When the TV row closes, focus goes back to the button that opened it.
    val triggerFocus = remember { FocusRequester() }
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(isOpen) {
        if (wasOpen && !isOpen) triggerFocus.requestFocus()
        wasOpen = isOpen
    }
    val regular = remember(chapters) { chapters.filterNot { it.detected } }
    val detected = remember(chapters) { chapters.filter { it.detected } }
    Box {
        IconButton(
            onClick = {
                if (tv) onMenuToggle(!isOpen)
                else { expandedLocal = true; onMenuToggle(true) }
            },
            modifier = Modifier.size(36.dp).focusRequester(triggerFocus).then(focusDecoration("chapters", 999f, 1.08f)),
        ) {
            @Suppress("DEPRECATION")
            Icon(Icons.Outlined.List, "Chapters", tint = colors.primary, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(
            expanded = !tv && expandedLocal, onDismissRequest = { expandedLocal = false; onMenuToggle(false) },
            offset = DpOffset(0.dp, (-48).dp), shape = RoundedCornerShape(8.dp),
            containerColor = colors.menuBackground,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .heightIn(max = 360.dp)
                .padding(horizontal = MenuFocusGutter),
        ) {
            val currentIdx = regular.indexOfLast { it.startMs <= currentMs() }.coerceAtLeast(0)
            if (regular.isNotEmpty()) {
                Text(
                    "CHAPTERS", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                    color = colors.primaryFaint, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                regular.forEachIndexed { idx, chapter ->
                    val isCurrent = idx == currentIdx
                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(
                                    formatTime(chapter.startMs),
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                    color = if (isCurrent) colors.accent else colors.primaryFaint,
                                )
                                Text(
                                    chapter.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isCurrent) colors.accent else colors.primaryDim,
                                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 2,
                                )
                            }
                        },
                        onClick = { onSelectChapter(chapter); expandedLocal = false; onMenuToggle(false) },
                        leadingIcon = if (isCurrent) { { Icon(Icons.Outlined.PlayArrow, null, tint = colors.accent, modifier = Modifier.size(16.dp)) } } else null,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                        modifier = Modifier.heightIn(min = 40.dp).then(focusDecoration("chapter.$idx", 8f, 1f)),
                    )
                }
            }
            if (detected.isNotEmpty()) {
                Text(
                    "DETECTED", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                    color = colors.primaryFaint, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                detected.forEachIndexed { idx, seg ->
                    DropdownMenuItem(
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    seg.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.primaryDim,
                                )
                                Text(
                                    "${formatTime(seg.startMs)}-${formatTime(seg.endMs ?: seg.startMs)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.primaryFaint,
                                )
                            }
                        },
                        onClick = { onSelectChapter(seg); expandedLocal = false; onMenuToggle(false) },
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        modifier = Modifier.heightIn(min = 48.dp).then(focusDecoration("segment.$idx", 8f, 1f)),
                    )
                }
            }
        }
    }
}

private const val SkipStepMs = 10_000L
private const val SkipBurstWindowMs = 800L

private data class SkipBurst(val forward: Boolean, val anchorMs: Long, val taps: Int) {
    val seconds get() = taps * SkipStepMs / 1000
    val targetMs get() = anchorMs + if (forward) taps * SkipStepMs else -taps * SkipStepMs
}

@Composable
private fun SideIndicator(
    icon: ImageVector,
    fraction: Float,
    label: String,
    colors: PlayerColors,
) {
    val animated by animateFloatAsState(targetValue = fraction.coerceIn(0f, 1f), animationSpec = tween(120))
    Column(
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
            .padding(vertical = 14.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(24.dp))
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(120.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(colors.seekBarInactive),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(animated)
                    .background(colors.seekBarActive),
            )
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = colors.primary)
    }
}

private class Throttle(private val window: Duration) {
    private var last: TimeMark? = null

    fun pass(): Boolean {
        val previous = last
        if (previous != null && previous.elapsedNow() < window) return false
        last = TimeSource.Monotonic.markNow()
        return true
    }
}
