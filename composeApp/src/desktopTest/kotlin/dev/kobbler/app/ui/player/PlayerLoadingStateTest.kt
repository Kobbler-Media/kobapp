package dev.kobbler.app.ui.player

import dev.kobbler.app.runUiTest
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.kobfocus.FocusScopeHost
import dev.kobbler.kobfocus.FocusScopeStore
import io.ktor.client.HttpClient
import kotlin.test.Test

/**
 * Nothing that reads as a control is drawn over a stream that has not opened yet.
 *
 * The transport is composed from a position and a duration the player does not have
 * during the open, so it draws 0:00 / 0:00, then a duration, then a resume position —
 * a control bar visibly assembling itself under a viewer who has not been given
 * anything to control. The title's own artwork is the honest thing to show instead.
 */
@OptIn(ExperimentalTestApi::class)
class PlayerLoadingStateTest {

    private fun state(starting: Boolean) = GlobalPlayerState().apply {
        mediaType = "movie"
        mediaId = "mov-1"
        sessionId = "sess-1"
        title = "Project Hail Mary"
        subtitle = "2026 • 2h 37m"
        isStarting = starting
    }

    private fun runPlayer(starting: Boolean, assertions: ComposeUiTest.() -> Unit) =
        runUiTest {
            mainClock.autoAdvance = false
            val player = FakePlayer().apply { durationMs.value = 9_444_000 }
            setContent {
                Box(Modifier.size(960.dp, 540.dp)) {
                    FocusScopeHost(id = "test", store = FocusScopeStore()) {
                        KobappTheme(formFactor = FormFactor.TV) {
                            FullscreenVideoPlayer(
                                player = player,
                                state = state(starting),
                                api = KobblerApi(HttpClient(), "http://127.0.0.1:1", "token"),
                                formFactor = FormFactor.TV,
                                onBack = {},
                                isFullscreen = true,
                                videoSurface = { },
                                onOpenGuide = {},
                            )
                        }
                    }
                }
            }
            repeat(4) { mainClock.advanceTimeByFrame() }
            assertions()
        }

    @Test
    fun `no transport while the stream is opening`() = runPlayer(starting = true) {
        onNodeWithContentDescription("Seek").assertDoesNotExist()
        onNodeWithContentDescription("Play/Pause").assertDoesNotExist()
        onNodeWithContentDescription("Back").assertDoesNotExist()
    }

    @Test
    fun `the transport is there once the stream is open`() = runPlayer(starting = false) {
        onNodeWithContentDescription("Seek").assertIsDisplayed()
        // The TV's transport is the bar and the status label, not the disc.
        onNodeWithContentDescription("Paused").assertIsDisplayed()
    }
}
