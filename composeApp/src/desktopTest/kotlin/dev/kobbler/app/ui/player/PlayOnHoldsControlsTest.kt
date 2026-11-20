package dev.kobbler.app.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.CastRouter
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.platform.PlaybackProtocol
import dev.kobbler.app.UiTimeoutMs
import dev.kobbler.app.runUiTest
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.kobfocus.FocusScopeHost
import dev.kobbler.kobfocus.FocusScopeStore
import dev.kobbler.kobplayer.PlayerState
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The picker is drawn from the button in the player's own chrome. Timing out under it
 * disposed the button, and the picker went with it mid-choice.
 */
@OptIn(ExperimentalTestApi::class)
class PlayOnHoldsControlsTest {

    private fun ComposeUiTest.pickerShown() = onAllNodesWithText("Play on").fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.controlsShown() = onAllNodesWithContentDescription("Play on").fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `the controls stay up while the picker is open and time out once it closes`() = runUiTest {
        val backend = FakeBackend(PlaybackProtocol.GoogleCast).apply { found("Projector TV") }
        val player = FakePlayer().apply {
            durationMs.value = 1_800_000
            currentMs.value = 10_000
            isPlaying.value = true
            state.value = PlayerState.PLAYING
        }
        val state = GlobalPlayerState().apply {
            mediaType = "movie"
            mediaId = "mov-1"
            sessionId = "sess-1"
            title = "A Film"
            isFullscreen = true
        }
        setContent {
            Box(Modifier.size(1280.dp, 720.dp)) {
                CompositionLocalProvider(LocalCastRouter provides CastRouter(listOf(backend))) {
                    FocusScopeHost(id = "test", store = FocusScopeStore()) {
                        KobappTheme(formFactor = FormFactor.Desktop) {
                            FullscreenVideoPlayer(
                                player = player,
                                state = state,
                                api = KobblerApi(HttpClient(), "http://127.0.0.1:1", "token"),
                                formFactor = FormFactor.Desktop,
                                onBack = {},
                                isFullscreen = true,
                                videoSurface = { },
                            )
                        }
                    }
                }
            }
        }
        waitForIdle()
        revealControls(FormFactor.Desktop)
        onNodeWithContentDescription("Play on").performClick()
        waitUntil(timeoutMillis = UiTimeoutMs) { pickerShown() }

        mainClock.advanceTimeBy(10_000)
        assertEquals(true, pickerShown(), "the picker went with the controls")
        assertEquals(true, controlsShown(), "controls timed out under the picker")

        onNodeWithText("Cancel").performClick()
        // A pointer resting over the player reads as activity and would hold the controls itself.
        onRoot().performMouseInput { exit() }
        waitUntil(timeoutMillis = UiTimeoutMs) {
            mainClock.advanceTimeBy(1_000)
            !controlsShown()
        }
        assertEquals(false, pickerShown())
    }
}
