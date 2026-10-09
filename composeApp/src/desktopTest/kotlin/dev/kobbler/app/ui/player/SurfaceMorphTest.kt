package dev.kobbler.app.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The player must arrive at its size, not grow into it.
 *
 * The surface is the only thing painting the player's background, so a surface that
 * animates open from nothing is a black rectangle expanding across whatever screen the
 * viewer just left — and on Android it is also a SurfaceView with no area for most of
 * half a second, which is not a window a hardware decoder can be handed.
 */
@OptIn(ExperimentalTestApi::class)
class SurfaceMorphTest {

    private val screen = IntSize(1920, 1080)
    private val fullscreen = IntRect(0, 0, 1920, 1080)

    /**
     * Measured on a Chromecast HD: the container reported 1920x1080 from layout while the
     * effect still held the rect measured against no container at all. Both inputs look
     * fine on their own, which is why the surface was placed at nothing and then spent
     * 380ms growing back out of the corner.
     */
    @Test
    fun `a stale empty target is not placed, however real the container is`() {
        assertEquals(MorphStep.Wait, morphStep(screen, IntRect(0, 0, 0, 0), placed = false))
    }

    @Test
    fun `nothing happens before the container has a size`() {
        assertEquals(MorphStep.Wait, morphStep(IntSize.Zero, IntRect(0, 0, 0, 0), placed = false))
    }

    @Test
    fun `the first real target is placed`() {
        assertEquals(MorphStep.Place, morphStep(screen, fullscreen, placed = false))
    }

    @Test
    fun `every target after the first is animated into`() {
        assertEquals(MorphStep.Animate, morphStep(screen, IntRect(0, 0, 200, 112), placed = true))
    }

    private val mini = IntSize(200, 112)

    /**
     * A SurfaceView is not moved by its own layout: the compositor repositions it a beat
     * later and it holds the last frame it was given until then. Animating between two
     * large rects therefore shows the video at the rect it is leaving for the whole
     * animation and then jumps.
     */
    @Test
    fun `growing a large surface into fullscreen is not animated`() {
        assertEquals(false, morphAnimates(IntSize(480, 270), PlayerMode.Fullscreen, mini))
    }

    /** The mini player is the morph's reason to exist: small, dragged, and pointer-driven. */
    @Test
    fun `shrinking into the mini player is animated`() {
        assertEquals(true, morphAnimates(IntSize(1920, 1080), PlayerMode.Mini, mini))
    }

    @Test
    fun `growing out of the mini player is animated`() {
        assertEquals(true, morphAnimates(mini, PlayerMode.Fullscreen, mini))
    }

    /** The mini/fullscreen morph is the reason any of this animates; it has to survive. */
    @Test
    fun `the morph still animates between modes`() = runComposeUiTest {
        mainClock.autoAdvance = false
        var mini by mutableStateOf(false)
        val seen = mutableListOf<IntSize>()

        setContent {
            var container by remember { mutableStateOf(IntSize.Zero) }
            val offset = remember { Animatable(IntOffset.Zero, IntOffset.VectorConverter) }
            val size = remember { Animatable(IntSize.Zero, IntSize.VectorConverter) }
            val target =
                if (mini) IntRect(0, 0, 200, 112)
                else IntRect(0, 0, container.width, container.height)
            Box(Modifier.fillMaxSize().onSizeChanged { container = it }) {
                SurfaceMorphEffect(container, target, offset, size)
                seen += size.value
            }
        }
        repeat(4) { mainClock.advanceTimeByFrame() }
        val full = seen.last()
        assertTrue(full.width > 200, "the surface never reached fullscreen: $seen")

        mini = true
        repeat(6) { mainClock.advanceTimeByFrame() }

        val between = seen.filter { it != full && it.width > 200 }
        assertTrue(between.isNotEmpty(), "the surface jumped straight to the mini size: $seen")
    }
}
