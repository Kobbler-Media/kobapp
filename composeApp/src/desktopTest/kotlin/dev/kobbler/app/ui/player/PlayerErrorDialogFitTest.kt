package dev.kobbler.app.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.StreamOption
import dev.kobbler.app.data.StreamQuality
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.runUiTest
import dev.kobbler.app.theme.KobappTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The incident's dialog grew past the screen with its trace and release list open and took
 * its buttons with it: on a TV, the row that held Want was below the overscan edge.
 */
private const val MIN_BUTTON_PX = 40f

@OptIn(ExperimentalTestApi::class)
class PlayerErrorDialogFitTest {

    private data class Screen(val name: String, val formFactor: FormFactor, val width: Dp, val height: Dp)

    private val screens = listOf(
        Screen("tv with overscan", FormFactor.TV, 900.dp, 480.dp),
        Screen("small phone portrait", FormFactor.Phone, 360.dp, 600.dp),
        Screen("phone landscape", FormFactor.Phone, 640.dp, 320.dp),
    )

    private val alternatives = (1..6).map {
        StreamOption(
            id = "o$it", releaseTitle = "Show.S04E17.720p.v$it", size = 1, indexer = "idx", infoHash = "h$it",
            quality = StreamQuality("720p", "h264", null, null), qualityJson = null, totalScore = 1.0,
            debridProvider = "realdebrid", debridProviderId = "p1", cached = true,
            sourceType = "torrent", fileId = null, publishDate = null,
        )
    }

    private val trace = buildString {
        appendLine("Playback failed")
        appendLine()
        appendLine("Show: Everybody Loves Raymond")
        appendLine("Tried 1 of 7 stream options")
        appendLine()
        append("Exception: Stream stopped at 0s of 1366s")
        repeat(40) { append("\n\tat dev.kobbler.kobplayer.AndroidPlayer.frame$it(AndroidPlayer.kt:$it)") }
    }

    private val buttons = listOf("Hide details", "Want", "Cancel", "Try another")

    private fun SemanticsNodeInteractionsProvider.boundsOf(text: String): Rect =
        onNodeWithText(text).fetchSemanticsNode().boundsInRoot

    @Test
    fun `every button stays on screen with the trace and the release list open`() {
        for (screen in screens) runUiTest {
            setContent {
                Box(Modifier.size(screen.width, screen.height).testTag("screen")) {
                    KobappTheme(formFactor = screen.formFactor) {
                        PlayerErrorDialog(
                            message = trace,
                            title = "Stream failed",
                            summary = "That stream wouldn't play.",
                            want = WantAction {},
                            alternatives = alternatives,
                            onDismiss = {},
                        )
                    }
                }
            }
            onNodeWithText("Details").performClick()
            onNodeWithText("Choose a release").performClick()
            waitForIdle()

            val visible = onNodeWithTag("screen").fetchSemanticsNode().boundsInRoot
            for (label in buttons) {
                val b = boundsOf(label)
                assertTrue(b.height >= MIN_BUTTON_PX, "${screen.name}: \"$label\" was squeezed to ${b.height}px")
                assertTrue(
                    b.top >= visible.top && b.bottom <= visible.bottom && b.left >= visible.left && b.right <= visible.right,
                    "${screen.name}: \"$label\" at $b is outside the screen $visible",
                )
                onNode(hasClickAction() and hasText(label)).assert(isFocusable())
            }
        }
    }
}
