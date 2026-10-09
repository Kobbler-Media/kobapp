package dev.kobbler.app.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.launch

internal const val MORPH_POSITION_MS = 260
internal const val MORPH_SIZE_MS = 380

internal enum class MorphStep { Wait, Place, Animate }

/**
 * What to do with a surface that wants to be at [target].
 *
 * The first target is placed, because there is nothing to morph *from* — the surface is
 * arriving, not moving. Everything after it is a mode change with a shape already on
 * screen, and those animate.
 *
 * [Wait] on an empty target is the load-bearing case. The container reports its size from
 * layout, which lands after the composition that read it, so a target measured against a
 * container of no size routinely meets a container that has one. Placing it there marks
 * the surface as arrived at nothing, and every later target then animates — which is a
 * black rectangle growing out of the top-left corner across the screen the viewer just
 * left, for as long as [MORPH_SIZE_MS]. Neither input alone rules that out: the container
 * is real and the rect is empty.
 */
internal fun morphStep(
    container: IntSize,
    target: IntRect,
    placed: Boolean,
    animates: Boolean = true,
): MorphStep = when {
    container == IntSize.Zero -> MorphStep.Wait
    target.width <= 0 || target.height <= 0 -> MorphStep.Wait
    !placed || !animates -> MorphStep.Place
    else -> MorphStep.Animate
}

/**
 * Whether a mode change is worth animating.
 *
 * A SurfaceView is not moved by its own layout: the compositor repositions it a beat
 * later, and until then it holds the frame it was last given at the rect it was last
 * given. Tweening between two large rects therefore does not move the video at all — it
 * sits where it was for the length of the animation and then jumps. Snapping is what
 * reads as "it arrived".
 *
 * The mini player is the one shape worth animating: it is small, it is dragged, and it
 * only exists where there is a pointer.
 */
internal fun morphAnimates(fromSize: IntSize, toMode: PlayerMode, miniSize: IntSize): Boolean =
    toMode == PlayerMode.Mini ||
        (fromSize.width <= miniSize.width && fromSize.height <= miniSize.height)

/** Drives [offset] and [size] to [targetRect] per [morphStep]. */
@Composable
internal fun SurfaceMorphEffect(
    containerSize: IntSize,
    targetRect: IntRect,
    offset: Animatable<IntOffset, AnimationVector2D>,
    size: Animatable<IntSize, AnimationVector2D>,
    skipAnimation: () -> Boolean = { false },
    animates: () -> Boolean = { true },
) {
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(targetRect, containerSize) {
        when (morphStep(containerSize, targetRect, placed, animates())) {
            MorphStep.Wait -> return@LaunchedEffect
            MorphStep.Place -> {
                offset.snapTo(IntOffset(targetRect.left, targetRect.top))
                size.snapTo(IntSize(targetRect.width, targetRect.height))
                placed = true
            }
            MorphStep.Animate -> {
                if (skipAnimation()) return@LaunchedEffect
                launch {
                    offset.animateTo(
                        IntOffset(targetRect.left, targetRect.top),
                        tween(durationMillis = MORPH_POSITION_MS, easing = FastOutSlowInEasing),
                    )
                }
                launch {
                    size.animateTo(
                        IntSize(targetRect.width, targetRect.height),
                        tween(durationMillis = MORPH_SIZE_MS, easing = FastOutSlowInEasing),
                    )
                }
            }
        }
    }
}
