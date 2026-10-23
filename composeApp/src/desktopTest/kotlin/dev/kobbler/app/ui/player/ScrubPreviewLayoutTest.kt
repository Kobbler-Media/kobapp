package dev.kobbler.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.kobbler.kobplayer.ui.VideoPlayerOverlay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A scrub preview is a hint about where a seek would land, so the bar it is a hint about
 * has to stay under the thumb while it is up. It does because the transport is anchored
 * to the bottom edge and grows upward, which is a property of one `align` on a column
 * three controls above the preview — nothing local says so, and anchoring the transport
 * any other way would send the bar 135dp up the screen on every press of a direction.
 */
@OptIn(ExperimentalTestApi::class)
class ScrubPreviewLayoutTest {

    @Test
    fun `the scrub preview does not displace the transport`() = runComposeUiTest {
        setContent {
            VideoPlayerOverlay(
                title = "A Movie",
                isPlaying = true,
                position = MutableStateFlow(60_000L),
                durationMs = 7_680_000L,
                onPlayPause = {},
                onSeek = {},
                onBack = {},
                thumbnailPreview = { Box(Modifier.size(240.dp, 135.dp).background(Color.Gray).semantics { contentDescription = "Preview" }) },
            )
        }
        waitForIdle()
        mainClock.advanceTimeBy(500)
        waitForIdle()

        val bar = onNodeWithContentDescription("Seek")
        val before = bar.fetchSemanticsNode().boundsInRoot.top

        // Held off the 400ms commit that clears the scrub: the displacement is what the
        // viewer sees for as long as they are still pressing a direction.
        mainClock.autoAdvance = false
        bar.requestFocus()
        bar.performKeyInput { pressKey(Key.DirectionRight) }
        mainClock.advanceTimeByFrame()

        onNodeWithContentDescription("Preview").assertIsDisplayed()
        assertEquals(
            before, bar.fetchSemanticsNode().boundsInRoot.top,
            "the preview pushed the seek bar off its baseline",
        )
    }
}
