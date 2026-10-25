package dev.kobbler.app.ui.player

import dev.kobbler.app.runUiTest
import dev.kobbler.app.UiTimeoutMs
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.kobfocus.FocusScope
import dev.kobbler.kobfocus.FocusScopeHost
import dev.kobbler.kobfocus.FocusScopeStore
import dev.kobbler.kobfocus.LocalFocusScope
import io.ktor.client.HttpClient
import kotlin.test.Test

/**
 * Anything the player draws over itself takes the D-pad with it, and has to give it back.
 *
 * The layer's own focus is not the player's: going [focusInert] evicts whatever was lit
 * down here, and a layer that hosts its own scope — subtitle search does — hands nothing
 * back when it closes. Focus then sits nowhere, and since the transport is only summoned
 * from its own key handler, no press can recover it.
 */
@OptIn(ExperimentalTestApi::class)
class PlayerLayerFocusTest {

    private fun playing() = GlobalPlayerState().apply {
        mediaType = "movie"
        mediaId = "mov-1"
        title = "A Film"
        sessionId = "sess-1"
        isFullscreen = true
    }

    private fun ComposeUiTest.showPlayer(state: GlobalPlayerState, api: KobblerApi): FocusScope {
        lateinit var scope: FocusScope
        setContent {
            Box(Modifier.size(1280.dp, 720.dp)) {
                FocusScopeHost(id = "test", store = FocusScopeStore()) {
                    scope = LocalFocusScope.current
                    KobappTheme(formFactor = FormFactor.TV) {
                        FullscreenVideoPlayer(
                            player = FakePlayer(),
                            state = state,
                            api = api,
                            formFactor = FormFactor.TV,
                            onBack = {},
                            isFullscreen = true,
                            videoSurface = { },
                        )
                    }
                }
            }
        }
        waitForIdle()
        revealControls(FormFactor.TV)
        return scope
    }

    @Test
    fun `closing subtitle search gives the D-pad back to the player`() = runUiTest {
        val api = KobblerApi(HttpClient(), "http://127.0.0.1:1", "token")
        val scope = showPlayer(playing(), api)

        onNodeWithContentDescription("Subtitles").performClick()
        waitForIdle()
        onNodeWithText("Find subtitles…").performClick()
        waitUntil(timeoutMillis = UiTimeoutMs) { scope.focused?.startsWith("player.") != true }

        // The player has a Back arrow of its own underneath; this is the panel's.
        onAllNodesWithContentDescription("Back").onLast().performClick()

        waitUntil(timeoutMillis = UiTimeoutMs) { scope.focused?.startsWith("player.") == true }
    }
}
