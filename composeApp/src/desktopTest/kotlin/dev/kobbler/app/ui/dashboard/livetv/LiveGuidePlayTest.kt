package dev.kobbler.app.ui.dashboard.livetv

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.LiveChannel
import dev.kobbler.app.data.LiveGuideChannel
import dev.kobbler.app.data.LiveGuideEntry
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.kobfocus.FocusScopeHost
import dev.kobbler.kobfocus.FocusScopeStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes

/**
 * Picking the programme that is on air is how a viewer starts watching. Both guides
 * answered a press on it by selecting it — which is what moving onto it had already
 * done — so the one obvious gesture in the whole page did nothing at all.
 */
@OptIn(ExperimentalTestApi::class)
class LiveGuidePlayTest {

    private val windowStart = nowInstant() - 10.minutes

    private fun channel(id: String) = LiveChannel(
        id = id, name = "Channel $id", lcn = "9", callsign = null, logoUrl = null,
        network = null, region = null, kind = null, hd = false, enabled = true,
        favourite = false, muxLabel = null, typeLabel = null, hasGuide = true,
        guideEntryCount = 1, videoCodec = null, audioCodec = null, signalQuality = null,
    )

    private fun entry(id: String, title: String, offsetMinutes: Int) =
        LiveGuideEntry(
            id = id, channelId = "c1",
            startTime = (windowStart + offsetMinutes.minutes).toString(),
            endTime = (windowStart + (offsetMinutes + 30).minutes).toString(),
            title = title, episodeNumber = null, episodeTitle = null, synopsis = null,
            genres = emptyList(), imageUrl = null, posterUrl = null, isFirst = false,
            episodeKey = null, durationSeconds = 1800,
            progressPct = null, recordingId = null, recordingStatus = null, repeatCount = 0,
        )

    private val guide = listOf(
        LiveGuideChannel(
            channel = channel("c1"),
            entries = listOf(
                entry("e1", "On Air Now", offsetMinutes = 0),
                entry("e2", "On Later", offsetMinutes = 30),
            ),
        ),
    )

    private class Watched {
        var channel: LiveChannel? = null
        var selected: LiveGuideSelection? = null
    }

    private fun pick(title: String, compact: Boolean, body: Watched.() -> Unit) {
        val watched = Watched()
        runComposeUiTest {
            setContent {
                FocusScopeHost(id = "guide", store = FocusScopeStore(), restoreEnabled = false) {
                    KobappTheme(formFactor = if (compact) FormFactor.Phone else FormFactor.TV) {
                        if (compact) {
                            LivePhoneGuide(
                                guide = guide,
                                watching = null,
                                loading = false,
                                accent = KobappTheme.colors.textHi,
                                gutter = 16.dp,
                                entryKey = null,
                                windowStart = windowStart,
                                windowMinutes = 240,
                                clock = GuideClock(),
                                modifier = Modifier.size(420.dp, 900.dp),
                                facetStrip = {},
                                onSelect = { watched.selected = it },
                                onWatch = { watched.channel = it },
                                onShiftWindow = {},
                            )
                        } else {
                            LiveGuideGrid(
                                guide = guide,
                                windowStart = windowStart,
                                windowMinutes = 240,
                                clock = GuideClock(),
                                selection = LiveGuideSelectionHolder(),
                                activeRecordings = emptyList(),
                                loading = false,
                                accent = KobappTheme.colors.textHi,
                                canWant = false,
                                gutter = 24.dp,
                                modifier = Modifier.size(1280.dp, 720.dp),
                                facetStrip = {},
                                onSelect = { watched.selected = it },
                                onWatch = { watched.channel = it },
                                onRecord = {},
                                onAddToWanted = {},
                                onShiftWindow = {},
                            )
                        }
                    }
                }
            }
            waitForIdle()
            mainClock.advanceTimeBy(1_000)
            waitForIdle()
            onNodeWithText(title).performClick()
            waitForIdle()
        }
        watched.body()
    }

    @Test
    fun `picking the programme on air in the grid starts watching it`() {
        pick("On Air Now", compact = false) { assertEquals("c1", channel?.id) }
    }

    @Test
    fun `picking the programme on air on a phone starts watching it`() {
        pick("On Air Now", compact = true) { assertEquals("c1", channel?.id) }
    }

    /** A programme that has not started has nothing to play; it stays a selection. */
    @Test
    fun `picking a later programme in the grid does not start watching`() {
        pick("On Later", compact = false) {
            assertNull(channel)
            assertEquals("e2", selected?.entry?.id)
        }
    }

    @Test
    fun `picking a later programme on a phone does not start watching`() {
        pick("On Later", compact = true) {
            assertNull(channel)
            assertEquals("e2", selected?.entry?.id)
        }
    }
}
