package dev.kobbler.app.ui.navigation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.kobbler.app.data.AuthState
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.LocalAuthState
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.player.GlobalPlayerState
import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The reader, comic viewer and audiobook player replace the scaffold outright, so BACK out
 * of one composes a brand-new scaffold over a stack that is still inside some tab. It must
 * pick that tab up rather than its rail's first row, which used to navigate to Home.
 */
@OptIn(ExperimentalTestApi::class)
class ScaffoldRecomposeTabTest {

    @Test
    fun `a fresh scaffold keeps the stack it was handed`() {
        val stacks = listOf(
            listOf(Destination.Tab.Books, Destination.BookDetail("b1")),
            listOf(Destination.Tab.Audiobooks, Destination.BookDetail("a1")),
            listOf(Destination.Tab.Books),
            listOf(Destination.Tab.TvShows, Destination.TvShowDetail("s1")),
        )
        for (formFactor in listOf(FormFactor.TV, FormFactor.Desktop)) {
            for (stack in stacks) {
                runDesktopComposeUiTest(width = 960, height = 540) {
                    StubServer(emptyMap()).use { stub ->
                        val nav = NavigationState()
                        stack.forEach(nav::navigate)
                        setContent {
                            val auth = AuthState(rememberCoroutineScope(), formFactor)
                            CompositionLocalProvider(LocalAuthState provides auth) {
                                KobappTheme(formFactor = formFactor) {
                                    DesktopScaffold(
                                        nav = nav,
                                        navConfig = defaultNavConfig,
                                        api = KobblerApi(HttpClient(), stub.url, "token"),
                                        formFactor = formFactor,
                                        player = null,
                                        gps = GlobalPlayerState(),
                                    )
                                }
                            }
                        }
                        mainClock.advanceTimeBy(1_000)
                        waitForIdle()
                        assertEquals(stack.last(), nav.current, "$formFactor $stack")
                    }
                }
            }
        }
    }
}
