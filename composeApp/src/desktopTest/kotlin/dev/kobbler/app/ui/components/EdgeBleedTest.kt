package dev.kobbler.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class EdgeBleedTest {

    private class Probe {
        var outerWidth = 0
        var outerX = 0
        var innerWidth = 0
        var innerX = 0
        var bleedPx = 0
    }

    private fun measure(bleed: Dp, gutter: Dp = 20.dp, parent: Dp = 300.dp): Probe {
        val probe = Probe()
        runComposeUiTest {
            setContent {
                probe.bleedPx = with(LocalDensity.current) { bleed.toPx() }.roundToInt()
                Box(Modifier.width(parent).padding(horizontal = gutter)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned {
                                probe.outerWidth = it.size.width
                                probe.outerX = it.positionInRoot().x.roundToInt()
                            }
                            .bleedHorizontally(bleed)
                            .onGloballyPositioned {
                                probe.innerWidth = it.size.width
                                probe.innerX = it.positionInRoot().x.roundToInt()
                            },
                    )
                }
            }
            waitForIdle()
        }
        return probe
    }

    @Test
    fun theRowIsWiderThanTheSpaceItOccupies() {
        val p = measure(bleed = 20.dp)
        assertEquals(p.outerWidth + p.bleedPx * 2, p.innerWidth)
    }

    @Test
    fun theRowStartsAtTheParentsOwnEdge() {
        val p = measure(bleed = 20.dp)
        assertEquals(p.outerX - p.bleedPx, p.innerX)
    }

    @Test
    fun bleedingByTheGutterReachesTheScreenEdge() {
        val p = measure(bleed = 20.dp, gutter = 20.dp, parent = 300.dp)
        assertEquals(0, p.innerX)
    }

    @Test
    fun zeroBleedChangesNothing() {
        val p = measure(bleed = 0.dp)
        assertEquals(p.outerWidth, p.innerWidth)
        assertEquals(p.outerX, p.innerX)
    }
}
