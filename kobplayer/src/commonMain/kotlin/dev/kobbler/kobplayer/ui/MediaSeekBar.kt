package dev.kobbler.kobplayer.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * A colored duration region on the seek bar (e.g. detected intro/credits).
 *
 * @param startFraction Region start [0..1]
 * @param endFraction Region end [0..1]
 * @param color Fill color; caller bakes alpha
 */
data class SeekBarRegion(
    val startFraction: Float,
    val endFraction: Float,
    val color: Color,
)

// A step lands on 1f exactly, but a live edge that is drawn from a polled buffer is only
// ever nearly there.
private const val AtEndFraction = 0.999f

/**
 * Keys that mean "the one you are on" — OK on a remote, Enter on a keyboard, Space where the
 * host treats it as play/pause. A TV remote sends [Key.DirectionCenter] for its middle button
 * and some send [Key.Enter] instead, so both are activate.
 */
internal val ActivateKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Spacebar)

/**
 * Slim media seek bar inspired by Spotify/YouTube.
 *
 * Idle: thin 3dp track, no visible thumb.
 * Active (touch/drag): 6dp track with a 14dp thumb dot.
 *
 * @param progress Current progress [0..1], read at draw time so a ticking playhead only redraws
 * @param onSeek Called with the new fraction when the user finishes seeking
 * @param onSeeking Called continuously during drag with the preview fraction
 * @param activeColor Fill color for played portion and thumb
 * @param inactiveColor Track background color
 * @param bufferedProgress Optional buffered amount [0..1] shown as a lighter fill
 * @param chapterMarkers Optional list of fractions [0..1] where chapter markers should be drawn
 * @param regions Optional colored duration regions (drawn behind buffered/progress fills)
 * @param focusDecoration Applied to the bar so the host app can supply its own focus ring
 * @param dpadStepFraction Fraction moved per LEFT/RIGHT press; 0 disables stepping
 * @param onPastEnd RIGHT pressed with the playhead already at the end. A live tune spends
 *   that press on its channel list, since there is nothing ahead of the live edge to seek
 *   to. Supplying it also makes the bar focusable on its own.
 * @param onActivate OK/Enter pressed while the bar holds focus. A remote lands on the bar,
 *   so the bar carries the transport: this is play/pause. Supplying it also makes the bar
 *   focusable on its own.
 */
@Composable
fun MediaSeekBar(
    progress: () -> Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onSeeking: ((Float) -> Unit)? = null,
    onHover: ((Float?) -> Unit)? = null,
    activeColor: Color = Color.White,
    inactiveColor: Color = Color.White.copy(alpha = 0.2f),
    bufferedColor: Color = Color.White.copy(alpha = 0.35f),
    bufferedProgress: Float = 0f,
    chapterMarkers: List<Float> = emptyList(),
    regions: List<SeekBarRegion> = emptyList(),
    idleTrackHeight: Dp = 3.dp,
    activeTrackHeight: Dp = 6.dp,
    thumbRadius: Dp = 7.dp,
    focusDecoration: Modifier = Modifier,
    dpadStepFraction: Float = 0f,
    onPastEnd: (() -> Unit)? = null,
    onActivate: (() -> Unit)? = null,
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var isHovered by remember { mutableStateOf(false) }
    var isFocused by remember { mutableStateOf(false) }
    var keyFraction by remember { mutableStateOf<Float?>(null) }

    val visuallyActive = isDragging || isHovered || isFocused
    val trackHeight by animateDpAsState(
        targetValue = if (visuallyActive) activeTrackHeight else idleTrackHeight,
        animationSpec = tween(150),
    )
    val currentThumbRadius by animateDpAsState(
        targetValue = if (visuallyActive) thumbRadius else 0.dp,
        animationSpec = tween(150),
    )

    fun displayFraction() = keyFraction ?: if (isDragging) dragFraction else progress().coerceIn(0f, 1f)
    val onHoverState by rememberUpdatedState(onHover)
    val onSeekState by rememberUpdatedState(onSeek)
    val onActivateState by rememberUpdatedState(onActivate)

    // A held D-pad direction fires one key event per repeat, so the seek is committed
    // once the presses stop rather than on every step.
    LaunchedEffect(keyFraction) {
        val target = keyFraction ?: return@LaunchedEffect
        delay(400)
        onSeekState(target)
        keyFraction = null
    }

    // Touch target is larger than visual track for easy grabbing
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp) // touch target
            .semantics { contentDescription = "Seek" }
            .then(focusDecoration)
            .onFocusChanged { isFocused = it.isFocused }
            .then(
                if (dpadStepFraction > 0f || onPastEnd != null || onActivateState != null) {
                    Modifier
                        .onKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                            // Left unanswered, the middle button was dropped on the floor here: the
                            // bar consumes LEFT/RIGHT and the overlay's own Enter handler only runs
                            // while the surface itself holds focus, which the bar now takes.
                            if (event.key in ActivateKeys) {
                                val activate = onActivateState ?: return@onKeyEvent false
                                activate()
                                return@onKeyEvent true
                            }
                            val base = keyFraction ?: displayFraction()
                            val forward = when (event.key) {
                                Key.DirectionLeft -> false
                                Key.DirectionRight -> true
                                else -> return@onKeyEvent false
                            }
                            val past = onPastEnd
                            if (forward && past != null && base >= AtEndFraction) {
                                past()
                                return@onKeyEvent true
                            }
                            if (dpadStepFraction <= 0f) return@onKeyEvent false
                            val next = (base + if (forward) dpadStepFraction else -dpadStepFraction)
                                .coerceIn(0f, 1f)
                            keyFraction = next
                            onSeeking?.invoke(next)
                            true
                        }
                        .focusable()
                } else {
                    Modifier
                },
            )
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                    onSeek(fraction)
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                        onSeeking?.invoke(dragFraction)
                    },
                    onDragEnd = {
                        isDragging = false
                        onSeek(dragFraction)
                    },
                    onDragCancel = {
                        isDragging = false
                    },
                    onHorizontalDrag = { change, _ ->
                        dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        onSeeking?.invoke(dragFraction)
                    },
                )
            }
            // Mouse hover: report fraction so callers can show a thumbnail preview.
            // Touch is excluded — finger contact would otherwise emit hover during taps.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val isMouse = event.changes.any { it.type == PointerType.Mouse }
                        if (!isMouse) continue
                        when (event.type) {
                            PointerEventType.Enter, PointerEventType.Move -> {
                                val pos = event.changes.firstOrNull()?.position ?: continue
                                val fraction = (pos.x / size.width).coerceIn(0f, 1f)
                                isHovered = true
                                onHoverState?.invoke(fraction)
                            }
                            PointerEventType.Exit -> {
                                isHovered = false
                                onHoverState?.invoke(null)
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight),
        ) {
            val trackH = size.height
            val cr = CornerRadius(trackH / 2, trackH / 2)
            val width = size.width

            // Inactive track
            drawRoundRect(
                color = inactiveColor,
                size = Size(width, trackH),
                cornerRadius = cr,
            )

            // Colored duration regions (behind buffered/progress so played portion paints over them)
            for (region in regions) {
                val start = region.startFraction.coerceIn(0f, 1f)
                val end = region.endFraction.coerceIn(0f, 1f)
                if (end <= start) continue
                drawRect(
                    color = region.color,
                    topLeft = Offset(width * start, 0f),
                    size = Size(width * (end - start), trackH),
                )
            }

            // Buffered progress
            if (bufferedProgress > 0f) {
                drawRoundRect(
                    color = bufferedColor,
                    size = Size(width * bufferedProgress.coerceIn(0f, 1f), trackH),
                    cornerRadius = cr,
                )
            }

            // Active progress
            val activeWidth = width * displayFraction()
            if (activeWidth > 0f) {
                drawRoundRect(
                    color = activeColor,
                    size = Size(activeWidth, trackH),
                    cornerRadius = cr,
                )
            }

            // Chapter markers — thin vertical ticks at chapter boundaries
            if (chapterMarkers.isNotEmpty()) {
                val markerColor = Color.White.copy(alpha = 0.4f)
                val markerWidth = 1.5f
                for (marker in chapterMarkers) {
                    if (marker <= 0f || marker >= 1f) continue
                    val x = width * marker
                    drawRect(
                        color = markerColor,
                        topLeft = Offset(x - markerWidth / 2, 0f),
                        size = Size(markerWidth, trackH),
                    )
                }
            }

            // Thumb dot. Under a remote it is also the focus indicator: the bar spans
            // the whole player, so ringing the control would draw a box around the
            // screen instead of pointing at the part that actually moves.
            val thumbR = currentThumbRadius.toPx()
            if (thumbR > 0f) {
                val centre = Offset(activeWidth.coerceIn(thumbR, width - thumbR), trackH / 2)
                if (isFocused) {
                    drawCircle(color = activeColor.copy(alpha = 0.28f), radius = thumbR * 2.4f, center = centre)
                    drawCircle(color = activeColor.copy(alpha = 0.5f), radius = thumbR * 1.6f, center = centre)
                }
                drawCircle(color = activeColor, radius = thumbR, center = centre)
            }
        }
    }
}
