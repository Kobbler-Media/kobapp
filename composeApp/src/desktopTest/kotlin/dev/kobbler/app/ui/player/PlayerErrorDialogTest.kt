package dev.kobbler.app.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.StreamOption
import dev.kobbler.app.data.StreamQuality
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A debrid failure leads with what happened in a sentence and what can be done about it;
 * the stack trace that used to be the whole dialog is one press away, for reports.
 */
@OptIn(ExperimentalTestApi::class)
class PlayerErrorDialogTest {

    private val alt = StreamOption(
        id = "o9", releaseTitle = "Show.S01E01.720p", size = 1, indexer = "idx", infoHash = "h9",
        quality = StreamQuality("720p", "h264", null, null), qualityJson = null, totalScore = 1.0,
        debridProvider = "realdebrid", debridProviderId = "p1", cached = true,
        sourceType = "torrent", fileId = null, publishDate = null,
    )

    @Test
    fun `the summary leads, the trace waits behind details, and Want asks for a download`() = runComposeUiTest {
        var wanted = 0
        var picked: StreamOption? = null
        setContent {
            Box(Modifier.size(1280.dp, 720.dp)) {
                KobappTheme(formFactor = FormFactor.TV) {
                    PlayerErrorDialog(
                        message = "Playback failed\n\nApiException: boom\n\tat Somewhere.kt:1",
                        title = "Couldn't play this",
                        summary = "None of the 3 streams we tried would play.",
                        want = WantAction { wanted++ },
                        alternatives = listOf(alt),
                        onPickAlternative = { picked = it },
                        onDismiss = {},
                    )
                }
            }
        }

        onNodeWithText("None of the 3 streams we tried would play.").assertIsDisplayed()
        onAllNodesWithText("at Somewhere.kt", substring = true).assertCountEquals(0)

        onNodeWithText("Details").performClick()
        onNodeWithText("at Somewhere.kt", substring = true).assertIsDisplayed()

        onNodeWithText("Want").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Wanted").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, wanted)

        onAllNodesWithText("720p").assertCountEquals(0)
        onNodeWithText("Choose a release").performClick()
        onNodeWithText("720p").assertIsDisplayed()

        onNodeWithText("Try another").performClick()
        assertEquals(alt, picked)
    }

    @Test
    fun `a failure with nothing friendly to say still shows the whole report`() = runComposeUiTest {
        setContent {
            KobappTheme(formFactor = FormFactor.TV) {
                PlayerErrorDialog(message = "Playback failed\n\nboom\n\tat Somewhere.kt:1", onDismiss = {})
            }
        }
        onNodeWithText("at Somewhere.kt", substring = true).assertIsDisplayed()
        onAllNodesWithText("Details").assertCountEquals(0)
        onAllNodesWithText("Want").assertCountEquals(0)
    }
}
