package dev.kobbler.app.ui.player

import dev.kobbler.app.runUiTest
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.kobfocus.FocusScopeHost
import dev.kobbler.kobfocus.FocusScopeStore
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The transport belongs on the bottom edge, at every shape a TV comes in.
 *
 * A 1080p TV at density 2 is 540dp tall — half the height a desktop window is tested at,
 * and under every "small screen" breakpoint in the app. A layout that only measures right
 * on the roomier of the two is a layout that is wrong on the device it ships to.
 */
@OptIn(ExperimentalTestApi::class)
class MovieTransportLayoutTest {

    private fun movieState() = GlobalPlayerState().apply {
        mediaType = "movie"
        mediaId = "mov-1"
        sessionId = "sess-1"
        title = "Project Hail Mary"
        subtitle = "2026 • 2h 37m"
    }

    private fun assertTransportOnBottomEdge(width: Dp, height: Dp) = runUiTest {
        val player = FakePlayer().apply { durationMs.value = 9_444_000 }
        setContent {
            Box(Modifier.size(width, height).semantics { contentDescription = "screen" }) {
                FocusScopeHost(id = "test", store = FocusScopeStore()) {
                    KobappTheme(formFactor = FormFactor.TV) {
                        FullscreenVideoPlayer(
                            player = player,
                            state = movieState(),
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
        waitForIdle()
        mainClock.advanceTimeBy(500)
        waitForIdle()

        val screen = onNodeWithContentDescription("screen").fetchSemanticsNode().boundsInRoot
        val bar = onNodeWithContentDescription("Seek").fetchSemanticsNode().boundsInRoot
        val gap = screen.bottom - bar.bottom

        assertTrue(
            gap < (screen.bottom - screen.top) / 8f,
            "on a ${width}x$height screen the transport ends ${gap}px short of the bottom",
        )
    }

    @Test
    fun `the transport sits on the bottom edge of a 1080p tv`() =
        assertTransportOnBottomEdge(960.dp, 540.dp)

    @Test
    fun `the transport sits on the bottom edge of a desktop window`() =
        assertTransportOnBottomEdge(1024.dp, 768.dp)
}
