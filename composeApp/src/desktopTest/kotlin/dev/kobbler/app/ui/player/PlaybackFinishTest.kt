package dev.kobbler.app.ui.player

import dev.kobbler.app.runUiTest
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.kobfocus.FocusScopeHost
import dev.kobbler.kobfocus.FocusScopeStore
import dev.kobbler.kobplayer.PlayerState
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A title that reaches its end gives the screen back.
 *
 * Only a show sets an onFinished handler, for chaining to the next episode. A film had
 * nothing to chain to and so had nothing at all: the stream ended and the player stayed
 * up over a stopped stream, with the credits frozen on screen and no indication that
 * anything had happened.
 */
@OptIn(ExperimentalTestApi::class)
class PlaybackFinishTest {

    private fun movieState() = GlobalPlayerState().apply {
        mediaType = "movie"
        mediaId = "mov-1"
        sessionId = "sess-1"
        title = "Project Hail Mary"
    }

    private fun runToEnd(
        state: GlobalPlayerState,
        onFinished: (() -> Unit)?,
        onBack: () -> Unit,
    ) = runUiTest {
        val player = FakePlayer().apply { durationMs.value = 9_444_000 }
        setContent {
            Box(Modifier.size(960.dp, 540.dp)) {
                FocusScopeHost(id = "test", store = FocusScopeStore()) {
                    KobappTheme(formFactor = FormFactor.TV) {
                        FullscreenVideoPlayer(
                            player = player,
                            state = state,
                            api = KobblerApi(HttpClient(), "http://127.0.0.1:1", "token"),
                            formFactor = FormFactor.TV,
                            onBack = onBack,
                            onFinished = onFinished,
                            isFullscreen = true,
                            videoSurface = { },
                            onOpenGuide = {},
                        )
                    }
                }
            }
        }
        waitForIdle()
        player.state.value = PlayerState.FINISHED
        waitForIdle()
        Thread.sleep(500)
        waitForIdle()
    }

    @Test
    fun `a film that ends hands the screen back`() {
        var backs = 0
        runToEnd(movieState(), onFinished = null, onBack = { backs++ })
        assertEquals(1, backs, "the player sat on a finished stream")
    }

    @Test
    fun `an episode that ends goes to the next one instead`() {
        var backs = 0
        var nexts = 0
        runToEnd(
            movieState().apply { mediaType = "episode" },
            onFinished = { nexts++ },
            onBack = { backs++ },
        )
        assertEquals(1, nexts, "the next episode was not started")
        assertEquals(0, backs, "the player was closed out from under the next episode")
    }

    @Test
    fun `the end of a film is remembered as watched, not as a position to resume`() {
        val state = movieState()
        runToEnd(state, onFinished = null, onBack = {})
        assertTrue(
            state.reportedPositions["mov-1"]?.finished == true,
            "finishing was recorded as a resume point: ${state.reportedPositions}",
        )
    }
}
