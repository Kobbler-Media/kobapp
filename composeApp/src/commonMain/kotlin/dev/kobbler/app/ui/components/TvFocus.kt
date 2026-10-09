package dev.kobbler.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.zIndex
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.theme.LocalKobappColors
import dev.kobbler.app.theme.LocalSection
import dev.kobbler.app.theme.accentFor
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

object TvFocusScale {
    const val None = 1f
    const val Subtle = 1.02f
    const val Standard = 1.04f
    const val Pronounced = 1.08f
}

private const val RingDp = 2f
private const val RingAlpha = 0.95f
private const val HaloDp = 3f
private const val HaloAlpha = 0.22f

private const val FocusInMillis = 150
private const val FocusOutMillis = 250

/**
 * Whether focus decoration draws at all.
 *
 * The content pane keeps real focus while the rail has the D-pad: screens claim entry
 * focus as their data lands, which is well after the viewer has moved on to the menu, so
 * a ring lights up behind an open rail and two things look focused at once. Suppressing
 * the decoration rather than the focus leaves every entry-focus race untouched — the ring
 * just fades in on whatever was already focused when the viewer steps back into content.
 *
 * Not static: ~150 call sites read this, and invalidating only the readers beats
 * recomposing the whole content pane on every trip to the rail.
 */
val LocalTvFocusDecor = compositionLocalOf { true }

class TvFocusState {
    var isFocused by mutableStateOf(false)
}

@Composable
fun rememberTvFocusState(): TvFocusState = remember { TvFocusState() }

fun Modifier.tvClickable(
    state: TvFocusState,
    onClick: () -> Unit,
): Modifier = this
    .onFocusChanged { state.isFocused = it.isFocused }
    .clickable(onClick = onClick)

@Composable
fun Modifier.tvFocusGlow(
    state: TvFocusState,
    cornerRadiusDp: Float = 8f,
    scale: Float = TvFocusScale.Standard,
): Modifier {
    val progress = tvFocusProgress(state.isFocused)
    return this
        .tvFocusScale(progress, scale)
        .kobFocusRing(progress, cornerRadiusDp, KobappTheme.accent)
}

fun Modifier.tvAutoGlow(
    cornerRadiusDp: Float = 8f,
    scale: Float = TvFocusScale.Standard,
): Modifier = this
    .zIndex(FocusedZIndex)
    .then(TvAutoGlowElement(cornerRadiusDp, snapFocusScale(scale)))

/**
 * The animation is deliberately returned unread: taking the [State] keeps the per-frame
 * read inside the layer and draw lambdas of [tvFocusScale] and [kobFocusRing] instead of
 * pulling the caller's whole composable into the animation's restart scope.
 */
@Composable
fun tvFocusProgress(focused: Boolean): State<Float> = animateFloatAsState(
    targetValue = if (focused && LocalTvFocusDecor.current) 1f else 0f,
    animationSpec = tween(if (focused) FocusInMillis else FocusOutMillis),
)

fun Modifier.tvFocusScale(progress: State<Float>, scale: Float): Modifier {
    val step = snapFocusScale(scale)
    return this.graphicsLayer {
        val current = 1f + ((step - 1f) * progress.value)
        scaleX = current
        scaleY = current
        clip = false
    }
}

// Call sites outside this package pass free-floating floats (1.01 … 1.1); snap
// them onto the named steps so every focused element grows by the same amount.
private fun snapFocusScale(scale: Float): Float = when {
    scale <= 1.005f -> TvFocusScale.None
    scale < 1.035f -> TvFocusScale.Subtle
    scale < 1.07f -> TvFocusScale.Standard
    else -> TvFocusScale.Pronounced
}

// Placement data, so it must not track the animation: a z that moves every frame
// re-places the whole parent rail. Every focusable carries the same constant, which
// leaves sibling order untouched while still lifting the ring and the scaled-up
// content above neighbours that draw no focus decoration of their own.
private const val FocusedZIndex = 1f

fun Modifier.kobFocusRing(progress: State<Float>, cornerRadiusDp: Float, accent: Color): Modifier =
    this.zIndex(FocusedZIndex).drawWithContent {
        drawContent()
        val p = progress.value
        if (p > 0.001f) drawFocusRing(p, cornerRadiusDp, accent)
    }

private fun DrawScope.drawFocusRing(progress: Float, cornerRadiusDp: Float, accent: Color) {
    val cr = cornerRadiusDp * density
    val ring = RingDp * density
    val halfRing = ring / 2f
    val halo = HaloDp * density
    val haloOffset = halfRing + halo / 2f
    drawRoundRect(
        color = Color.White.copy(alpha = RingAlpha * progress),
        topLeft = Offset(-halfRing, -halfRing),
        size = Size(size.width + ring, size.height + ring),
        cornerRadius = CornerRadius(cr + halfRing, cr + halfRing),
        style = Stroke(width = ring),
    )
    drawRoundRect(
        color = accent.copy(alpha = HaloAlpha * progress),
        topLeft = Offset(-haloOffset, -haloOffset),
        size = Size(size.width + haloOffset * 2, size.height + haloOffset * 2),
        cornerRadius = CornerRadius(cr + haloOffset, cr + haloOffset),
        style = Stroke(width = halo),
    )
}

private data class TvAutoGlowElement(
    val cornerRadiusDp: Float,
    val focusScale: Float,
) : ModifierNodeElement<TvAutoGlowNode>() {
    override fun create() = TvAutoGlowNode(cornerRadiusDp, focusScale)

    override fun update(node: TvAutoGlowNode) {
        node.cornerRadiusDp = cornerRadiusDp
        node.focusScale = focusScale
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "tvAutoGlow"
        properties["cornerRadiusDp"] = cornerRadiusDp
        properties["scale"] = focusScale
    }
}

private class TvAutoGlowNode(
    var cornerRadiusDp: Float,
    var focusScale: Float,
) : Modifier.Node(),
    DrawModifierNode,
    FocusEventModifierNode,
    CompositionLocalConsumerModifierNode,
    ObserverModifierNode {

    private var progress = Animatable(0f)
    private var animation: Job? = null
    private var focused = false
    private var decorate = true

    private val accent: Color
        get() = currentValueOf(LocalKobappColors).accentFor(currentValueOf(LocalSection))

    // Reading LocalTvFocusDecor from draw() would suppress the ring but not the fade, so
    // it is observed here instead: the node re-targets its own animation and the ring
    // eases out exactly as it does when focus genuinely leaves.
    override fun onAttach() = readDecor()

    override fun onObservedReadsChanged() = readDecor()

    private fun readDecor() {
        observeReads { decorate = currentValueOf(LocalTvFocusDecor) }
        retarget()
    }

    override fun onDetach() {
        animation?.cancel()
        animation = null
    }

    // Lazy rails hand a node to a different item rather than recreating it, and the new
    // item is not focused; without this the incoming card inherits the ring mid-fade.
    override fun onReset() {
        animation?.cancel()
        animation = null
        focused = false
        progress = Animatable(0f)
        if (isAttached) invalidateDraw()
    }

    override fun onFocusEvent(focusState: FocusState) {
        focused = focusState.isFocused
        retarget()
    }

    private fun retarget() {
        val target = if (focused && decorate) 1f else 0f
        if (target == progress.targetValue) return
        animation?.cancel()
        animation = coroutineScope.launch {
            progress.animateTo(
                targetValue = target,
                animationSpec = tween(if (target == 1f) FocusInMillis else FocusOutMillis),
            ) {
                this@TvAutoGlowNode.invalidateDraw()
            }
        }
    }

    override fun ContentDrawScope.draw() {
        val p = progress.value
        if (p <= 0.001f) {
            drawContent()
            return
        }
        val step = 1f + ((focusScale - 1f) * p)
        scale(step, step) {
            this@draw.drawContent()
            drawFocusRing(p, cornerRadiusDp, accent)
        }
    }
}

@Composable
fun Modifier.tvMarquee(focused: Boolean): Modifier =
    if (focused && LocalTvFocusDecor.current) {
        basicMarquee(
            iterations = Int.MAX_VALUE,
            initialDelayMillis = 800,
            repeatDelayMillis = 1200,
        )
    } else {
        this
    }
