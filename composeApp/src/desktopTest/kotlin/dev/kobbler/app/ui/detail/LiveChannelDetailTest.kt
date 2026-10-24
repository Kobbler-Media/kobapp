package dev.kobbler.app.ui.detail

import dev.kobbler.app.runUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.LiveChannel
import dev.kobbler.app.data.LiveGuideEntry
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.kobfocus.FocusScopeHost
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

@OptIn(ExperimentalTestApi::class)
class LiveChannelDetailTest {

    private val now = Clock.System.now()

    private val channel = LiveChannel(
        id = "c3", name = "SBS (Special Broadcasting Service)", lcn = "3", callsign = null, logoUrl = null,
        network = "SBS", region = null, kind = null, hd = false, enabled = true,
        favourite = false, muxLabel = null, typeLabel = "SD MPEG-2", hasGuide = true,
        guideEntryCount = 1, videoCodec = null, audioCodec = null, signalQuality = null,
    )

    private val later = LiveGuideEntry(
        id = "g9", channelId = "c3", startTime = (now + 2.hours).toString(), endTime = (now + 3.hours).toString(),
        title = "Mastermind", episodeNumber = null, episodeTitle = null, synopsis = null, genres = emptyList(),
        imageUrl = null, posterUrl = null, isFirst = false, episodeKey = null, durationSeconds = 3600,
        progressPct = null, recordingId = null, recordingStatus = null, repeatCount = 0,
    )

    private fun page(formFactor: FormFactor, width: Int, onSeriesRule: ((LiveGuideEntry) -> Unit)?, body: androidx.compose.ui.test.DesktopComposeUiTest.() -> Unit) =
        runUiTest(width = width, height = 800) {
            setContent {
                KobappTheme(formFactor = formFactor) {
                    FocusScopeHost(id = "channel") {
                        LiveChannelDetailScreen(
                            channel = channel,
                            entries = listOf(later),
                            api = KobblerApi(HttpClient(), "http://127.0.0.1:9", "token"),
                            formFactor = formFactor,
                            onSeriesRule = onSeriesRule,
                        )
                    }
                }
            }
            waitForIdle()
            body()
        }

    /** The grid's Want moved here with the rest of a channel's schedule. */
    @Test
    fun `a programme on the schedule seeds a series rule`() {
        var seeded: String? = null
        page(FormFactor.Desktop, 1280, onSeriesRule = { seeded = it.id }) {
            onNodeWithContentDescription("Record every episode of Mastermind").performClick()
        }
        assertEquals("g9", seeded)
    }

    @Test
    fun `without wanted access there is no series action`() {
        page(FormFactor.Desktop, 1280, onSeriesRule = null) {
            assertTrue(onAllNodesWithContentDescription("Record every episode of Mastermind").isEmpty())
        }
    }

    /** Beside the Watch button the name had a narrow column and wrapped a word a line. */
    @Test
    fun `on a phone the channel name sits above its actions, not squeezed beside them`() {
        page(FormFactor.Phone, 400, onSeriesRule = null) {
            val name = onNodeWithText(channel.name).getUnclippedBoundsInRoot()
            val watch = onNodeWithText("Watch").getUnclippedBoundsInRoot()
            assertTrue(name.bottom <= watch.top, "name $name is beside the Watch button $watch")
        }
    }

    private fun androidx.compose.ui.test.DesktopComposeUiTest.onAllNodesWithContentDescription(text: String) =
        onAllNodes(androidx.compose.ui.test.hasContentDescription(text)).fetchSemanticsNodes()
}
