package dev.kobbler.app.ui.navigation

import dev.kobbler.app.runUiTest
import dev.kobbler.app.UiTimeoutMs
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import dev.kobbler.app.data.AuthState
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.LocalAuthState
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.DataCache
import dev.kobbler.app.ui.player.GlobalPlayerState
import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals

/** The desktop window can go fullscreen from any tab's top bar, not just Home's. */
@OptIn(ExperimentalTestApi::class)
class FullscreenToggleTest {

    @Test
    fun `every desktop tab offers the fullscreen toggle`() {
        for (tab in listOf(Destination.Tab.Home, Destination.Tab.Movies, Destination.Tab.TvShows, Destination.Tab.Books)) {
            runUiTest(width = 1280, height = 800) {
                DataCache.clear()
                StubServer(emptyMap()).use { stub ->
                    var toggled = 0
                    val nav = NavigationState()
                    setContent {
                        val auth = AuthState(rememberCoroutineScope(), FormFactor.Desktop)
                        CompositionLocalProvider(LocalAuthState provides auth) {
                            KobappTheme(formFactor = FormFactor.Desktop) {
                                DesktopScaffold(
                                    nav = nav,
                                    navConfig = defaultNavConfig,
                                    api = KobblerApi(HttpClient(), stub.url, "token"),
                                    formFactor = FormFactor.Desktop,
                                    player = null,
                                    gps = GlobalPlayerState(),
                                    onToggleFullscreen = { toggled++ },
                                )
                            }
                        }
                    }
                    // The rail's debounced commit lands on its first row once at start, as a
                    // fresh launch does; the viewer's own move comes after it.
                    mainClock.advanceTimeBy(1_000)
                    runOnIdle { nav.navigate(tab) }
                    waitForIdle()
                    assertEquals(tab, nav.current)
                    waitUntil(timeoutMillis = UiTimeoutMs) {
                        onAllNodesWithContentDescription("Fullscreen").fetchSemanticsNodes().isNotEmpty()
                    }
                    onNodeWithContentDescription("Fullscreen").performClick()
                    assertEquals(1, toggled, "$tab")
                }
            }
        }
    }
}
