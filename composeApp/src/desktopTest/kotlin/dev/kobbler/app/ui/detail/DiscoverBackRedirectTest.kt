package dev.kobbler.app.ui.detail

import dev.kobbler.app.runUiTest
import dev.kobbler.app.UiTimeoutMs
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.data.AuthState
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.LocalAuthState
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.DataCache
import dev.kobbler.app.ui.navigation.DesktopScaffold
import dev.kobbler.app.ui.navigation.Destination
import dev.kobbler.app.ui.navigation.NavigationState
import dev.kobbler.app.ui.navigation.defaultNavConfig
import dev.kobbler.app.ui.player.GlobalPlayerState
import dev.kobbler.app.ui.player.StubServer
import dev.kobbler.kobfocus.FocusScopeStore
import dev.kobbler.kobfocus.LocalFocusScopeStore
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A stream from Discover creates the library title. The Discover page for it must not
 * survive in the back stack offering to stream it again — and sending the viewer on to the
 * library page must not leave a page behind that sends them forward again on BACK.
 */
@OptIn(ExperimentalTestApi::class)
class DiscoverBackRedirectTest {

    private val discover = Destination.DiscoverItemDetail("movie", "tmdb", "603")
    private val library = Destination.MovieDetail("m1")

    private fun page(test: DesktopComposeUiTest.(NavigationState, KobblerApi) -> Unit) =
        runUiTest(width = 1280, height = 720) {
            DataCache.clear()
            val inLibrary = AtomicBoolean(false)
            val routes = mapOf<String, (HttpExchange) -> String>(
                "/api/v1/admin/discover/movie/tmdb/603" to { _ ->
                    val lib = if (inLibrary.get()) "\"m1\"" else "null"
                    """{"media_type":"movie","provider":"tmdb","library_id":$lib,"is_in_library":${inLibrary.get()},
                       "details":{"title":"The Matrix","year":1999,"identifiers":{"tmdb_id":"603"}}}"""
                },
                "/api/v1/admin/movies/m1" to { _ -> """{"id":"m1","title":"The Matrix","year":1999}""" },
                "/api/v1/admin/stream/search" to { _ ->
                    """{"options":[{"id":"o1","release_title":"The.Matrix.1999.1080p","debrid_provider":"realdebrid",
                       "debrid_provider_id":"p1","info_hash":"h1","cached":true}]}"""
                },
                "/api/v1/admin/stream/resolve" to { _ ->
                    inLibrary.set(true)
                    """{"session_id":"s1","method":"direct_play","direct_url":"/api/v1/admin/stream/s1/play","media_id":"m1"}"""
                },
                "/api/v1/library/stats" to { _ -> """{"kobload_configured":true}""" },
            )
            StubServer(routes).use { stub ->
                val nav = NavigationState()
                val api = KobblerApi(HttpClient(), stub.url, "token")
                runBlocking { api.checkStreamingAvailability() }
                setContent {
                    val auth = AuthState(rememberCoroutineScope(), FormFactor.Desktop)
                    CompositionLocalProvider(LocalAuthState provides auth, LocalFocusScopeStore provides FocusScopeStore()) {
                        KobappTheme(formFactor = FormFactor.Desktop) {
                            DesktopScaffold(
                                nav = nav,
                                navConfig = defaultNavConfig,
                                api = api,
                                formFactor = FormFactor.Desktop,
                                player = null,
                                gps = GlobalPlayerState(),
                            )
                        }
                    }
                }
                runOnIdle { nav.navigate(discover) }
                waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithText("Play").fetchSemanticsNodes().isNotEmpty() }
                test(nav, api)
            }
        }

    private fun DesktopComposeUiTest.backLandsHomeAndStays(nav: NavigationState) {
        runOnIdle { nav.goBack() }
        mainClock.advanceTimeBy(2_000)
        waitForIdle()
        assertEquals(Destination.Tab.Home, nav.current, "BACK from the library page bounced forward")
    }

    @Test
    fun `streaming from the page swaps it for the library page`() = page { nav, _ ->
        onAllNodesWithText("Play")[0].performClick()
        waitUntil(timeoutMillis = UiTimeoutMs) { nav.current == library }
        backLandsHomeAndStays(nav)
    }

    @Test
    fun `a page left in the back stack refetches on return and becomes the library page`() = page { nav, api ->
        runOnIdle { nav.navigate(Destination.PersonDetail("p1")) }
        runBlocking {
            val detail = api.getDiscoverDetail("movie", "tmdb", "603")
            val option = api.streamSearch(discover = detail).options.first()
            api.streamResolve(option, "movie", autoCreate = detail)
        }
        runOnIdle { nav.goBack() }
        waitUntil(timeoutMillis = UiTimeoutMs) { nav.current == library }
        backLandsHomeAndStays(nav)
    }
}
