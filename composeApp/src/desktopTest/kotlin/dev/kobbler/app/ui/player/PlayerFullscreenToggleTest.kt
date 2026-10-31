package dev.kobbler.app.ui.player

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.runUiTest
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.kobfocus.FocusScopeHost
import dev.kobbler.kobfocus.FocusScopeStore
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Only a desktop window has a windowed mode worth toggling into. A phone plays landscape
 * and fullscreen, and a TV has nothing else.
 */
@OptIn(ExperimentalTestApi::class)
class PlayerFullscreenToggleTest {

    private fun offersToggle(formFactor: FormFactor): Boolean {
        var offered = false
        runUiTest {
            val player = FakePlayer().apply { durationMs.value = 600_000 }
            setContent {
                FocusScopeHost(id = "test", store = FocusScopeStore()) {
                    KobappTheme(formFactor = formFactor) {
                        FullscreenVideoPlayer(
                            player = player,
                            state = GlobalPlayerState().apply { mediaType = "movie"; mediaId = "m"; sessionId = "s"; title = "Film" },
                            api = KobblerApi(HttpClient(), "http://127.0.0.1:1", "token"),
                            formFactor = formFactor,
                            onBack = {},
                            onToggleFullscreen = {},
                            isFullscreen = true,
                            videoSurface = { },
                            onOpenGuide = {},
                        )
                    }
                }
            }
            waitForIdle()
            revealControls(formFactor)
            offered = listOf("Fullscreen", "Exit fullscreen").any {
                onAllNodesWithContentDescription(it).fetchSemanticsNodes().isNotEmpty()
            }
        }
        return offered
    }

    @Test
    fun `only the desktop player offers a fullscreen toggle`() {
        val offered = FormFactor.entries.associateWith(::offersToggle)
        assertEquals(mapOf(FormFactor.TV to false, FormFactor.Phone to false, FormFactor.Desktop to true), offered)
    }
}
