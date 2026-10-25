package dev.kobbler.app.ui.player

import dev.kobbler.app.runUiTest
import dev.kobbler.app.UiTimeoutMs
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.PlaybackSubtitleTrackDto
import dev.kobbler.app.data.SkipSegment
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.kobfocus.FocusScopeHost
import dev.kobbler.kobfocus.FocusScopeStore
import dev.kobbler.kobplayer.PlayerState
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The playhead moves several times a second for the whole of an episode. With the
 * controls hidden nothing on screen depends on it, and every tick that still invalidates
 * the composition costs the device a vsync and a recompose pass for nothing.
 */
@OptIn(ExperimentalTestApi::class)
class PositionTickIdleTest {

    private val vtt = """
        WEBVTT

        00:00:10.000 --> 00:00:14.000
        First line

        00:00:20.000 --> 00:00:24.000
        Second line
    """.trimIndent()

    private val intro = SkipSegment("intro", startSeconds = 60.0, endSeconds = 150.0)

    private fun ComposeUiTest.shows(text: String) =
        waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeUiTest.invalidationsWhile(ticks: () -> Unit): Int {
        val pending = AtomicInteger()
        val watcher = CoroutineScope(Dispatchers.Unconfined)
        Recomposer.runningRecomposers.value.forEach { info ->
            watcher.launch { info.state.collect { if (it == Recomposer.State.PendingWork) pending.incrementAndGet() } }
        }
        waitForIdle()
        pending.set(0)
        ticks()
        waitForIdle()
        watcher.cancel()
        return pending.get()
    }

    private fun playing(test: ComposeUiTest.(FakePlayer) -> Unit) =
        StubServer(mapOf("/subs/en.vtt" to { _ -> vtt })).use { server ->
            runUiTest {
                val player = FakePlayer().apply {
                    durationMs.value = 1_800_000
                    currentMs.value = 10_500
                    isPlaying.value = true
                    state.value = PlayerState.PLAYING
                }
                val state = GlobalPlayerState().apply {
                    mediaType = "episode"
                    mediaId = "ep-1"
                    sessionId = "sess-1"
                    title = "Episode"
                    skipSegments = listOf(intro)
                    serverSubtitleTracks = listOf(
                        PlaybackSubtitleTrackDto(trackId = "en", url = "${server.url}/subs/en.vtt", format = "vtt"),
                    )
                    selectedServerSubtitleTrackId = "en"
                    isFullscreen = true
                }
                setContent {
                    Box(Modifier.size(960.dp, 540.dp)) {
                        FocusScopeHost(id = "test", store = FocusScopeStore()) {
                            KobappTheme(formFactor = FormFactor.Phone) {
                                PersistentVideoSurface(
                                    player = player,
                                    state = state,
                                    api = KobblerApi(HttpClient(), server.url, "token"),
                                    formFactor = FormFactor.Phone,
                                    onToggleFullscreen = null,
                                    isFullscreen = true,
                                    scope = CoroutineScope(Dispatchers.Unconfined),
                                    videoSurface = { },
                                )
                            }
                        }
                    }
                }
                shows("First line")
                test(player)
            }
        }

    @Test
    fun `a tick that changes nothing on screen leaves the composition alone`() = playing { player ->
        mainClock.advanceTimeBy(6_000)
        waitForIdle()
        assertEquals(0, onAllNodesWithText("/ 30:00", substring = true).fetchSemanticsNodes().size, "controls still up")

        val withinCue = invalidationsWhile {
            for (ms in 10_750L..13_750L step 250) {
                player.currentMs.value = ms
                waitForIdle()
            }
        }
        assertEquals(0, withinCue, "ticks inside one subtitle cue, controls hidden")

        player.currentMs.value = 20_500
        shows("Second line")
        player.currentMs.value = 61_000
        shows("Skip Intro")
    }

    @Test
    fun `the shown controls still follow the playhead`() = playing { player ->
        revealControls(FormFactor.Phone)
        shows("0:10 / 30:00")
        player.currentMs.value = 12_500
        shows("0:12 / 30:00")
    }

    @Test
    fun `controls brought back show where the playhead got to while they were hidden`() = playing { player ->
        mainClock.advanceTimeBy(6_000)
        waitForIdle()
        player.currentMs.value = 95_000
        waitForIdle()
        onRoot().performTouchInput { click(center) }
        waitUntil(timeoutMillis = UiTimeoutMs) {
            onAllNodesWithText("/ 30:00", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("1:35 / 30:00").assertExists()
    }
}
