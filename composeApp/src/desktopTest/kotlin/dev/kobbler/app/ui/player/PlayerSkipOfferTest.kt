package dev.kobbler.app.ui.player

import dev.kobbler.app.runUiTest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.SkipSegment
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.KobButton
import dev.kobbler.kobfocus.FocusScopeHost
import dev.kobbler.kobfocus.FocusScopeStore
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A remote reaches Skip Intro / Skip Credits with OK, the moment it appears, controls up or not. */
@OptIn(ExperimentalTestApi::class)
class PlayerSkipOfferTest {

    private class Rig {
        var offered by mutableStateOf(true)
        var skips = 0
        val seeks = mutableListOf<Long>()
    }

    private fun tvPlayer(test: ComposeUiTest.(Rig) -> Unit) = runUiTest(width = 1920, height = 1080) {
        val rig = Rig()
        setContent {
            KobappTheme(formFactor = FormFactor.TV) {
                PlayerOverlay(
                    title = "Film",
                    isPlaying = true,
                    position = MutableStateFlow(60_000L),
                    durationMs = 600_000L,
                    onPlayPause = {},
                    onSeek = { rig.seeks += it },
                    onBack = {},
                    offer = if (rig.offered) { focus ->
                        KobButton(text = "Skip Credits", onClick = { rig.skips++ }, modifier = focus.align(Alignment.BottomEnd))
                    } else null,
                )
            }
        }
        mainClock.advanceTimeBy(500)
        waitForIdle()
        test(rig)
    }

    private fun ComposeUiTest.press(key: Key) {
        onRoot().performKeyInput { pressKey(key) }
        mainClock.advanceTimeBy(300)
        waitForIdle()
    }

    @Test
    fun `ok on a playing picture takes the offered skip`() = tvPlayer { rig ->
        onNodeWithText("Skip Credits").assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(1, rig.skips, "OK did not skip")
    }

    @Test
    fun `left and right still seek while a skip is offered`() = tvPlayer { rig ->
        press(Key.DirectionRight)
        assertEquals(listOf(70_000L), rig.seeks)
        assertEquals(0, rig.skips)
    }

    /** The controls time out and take focus with them; it goes back to the offer, not the bare picture. */
    @Test
    fun `the offer has focus again once the controls hide`() = tvPlayer { rig ->
        press(Key.DirectionUp)
        mainClock.advanceTimeBy(6_000)
        waitForIdle()
        onNodeWithText("Skip Credits").assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(1, rig.skips)
    }

    /** The picture takes the D-pad back, so OK brings up the controls as it does with no offer. */
    @Test
    fun `once the offer is gone ok brings up the controls again`() = tvPlayer { rig ->
        rig.offered = false
        mainClock.advanceTimeBy(300)
        waitForIdle()
        check(onAllNodesWithContentDescription("Playing").fetchSemanticsNodes().isEmpty()) { "the controls were already up" }
        press(Key.DirectionCenter)
        check(onAllNodesWithContentDescription("Playing").fetchSemanticsNodes().isNotEmpty()) { "OK went nowhere after the skip button left" }
        assertEquals(0, rig.skips)
    }

    /**
     * The real player, not just its overlay: playback runs into an intro long after the
     * page handed out its entry focus, and OK is then the skip.
     */
    @Test
    fun `ok during an intro skips it on a tv player`() = runUiTest(width = 960, height = 540) {
        val player = FakePlayer().apply {
            durationMs.value = 1_380_000L
            currentMs.value = 10_000L
            isPlaying.value = true
        }
        val state = GlobalPlayerState().apply {
            mediaType = "episode"
            mediaId = "ep-1"
            sessionId = "sess-1"
            title = "Episode"
            skipSegments = listOf(SkipSegment("intro", startSeconds = 30.0, endSeconds = 90.0))
        }
        setContent {
            FocusScopeHost(id = "test", store = FocusScopeStore()) {
                KobappTheme(formFactor = FormFactor.TV) {
                    FullscreenVideoPlayer(
                        player = player,
                        state = state,
                        api = KobblerApi(HttpClient(), "http://127.0.0.1:1", "token"),
                        formFactor = FormFactor.TV,
                        onBack = {},
                        isFullscreen = true,
                        videoSurface = { },
                    )
                }
            }
        }
        mainClock.advanceTimeBy(2_000)
        waitForIdle()
        // The viewer has already used the controls once, so the page's entry focus is spent.
        press(Key.DirectionUp)
        mainClock.advanceTimeBy(6_000)
        waitForIdle()
        player.currentMs.value = 32_000L
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        onNodeWithText("Skip Intro").assertIsFocused()
        player.calls.clear()
        press(Key.DirectionCenter)
        assertTrue(player.calls.any { it.startsWith("seek:90") }, "OK did not skip the intro: ${player.calls}")
    }
}
