package dev.kobbler.app.ui.dashboard

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
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
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Categories picker, driven by a remote: it used to be a dialog list the D-pad could not walk. */
@OptIn(ExperimentalTestApi::class)
class CategoryPageTest {

    private val genres = """{"genres":[
        {"name":"Action","count":4,"backdrop_url":"/api/v1/admin/movies/a1/backdrop"},
        {"name":"Comedy","count":1,"backdrop_url":null},
        {"name":"Drama","count":7,"backdrop_url":"/api/v1/admin/movies/d1/backdrop"}]}"""

    private fun moviesPage(test: DesktopComposeUiTest.(movieQueries: List<String>) -> Unit) =
        runDesktopComposeUiTest(width = 960, height = 540) {
            DataCache.clear()
            val queries = CopyOnWriteArrayList<String>()
            StubServer(
                mapOf(
                    "/api/v1/admin/discover" to { _: HttpExchange -> """{"watch_next":[],"recently_added":[],"sections":[]}""" },
                    "/api/v1/admin/dashboard" to { _: HttpExchange -> """{"recent_movies":[]}""" },
                    "/api/v1/admin/movies" to { ex: HttpExchange ->
                        queries += ex.requestURI.rawQuery.orEmpty()
                        """{"items":[{"id":"g1","title":"Grid 1","year":2020}],"total_count":1}"""
                    },
                    "/api/v1/admin/movies/genres" to { _: HttpExchange -> genres },
                    "/api/v1/admin/movies/available-letters" to { _: HttpExchange -> """{"letters":["G"]}""" },
                ),
            ).use { stub ->
                val nav = NavigationState()
                setContent {
                    val auth = AuthState(rememberCoroutineScope(), FormFactor.TV)
                    CompositionLocalProvider(LocalAuthState provides auth, LocalFocusScopeStore provides FocusScopeStore()) {
                        KobappTheme(formFactor = FormFactor.TV) {
                            DesktopScaffold(
                                nav = nav,
                                navConfig = defaultNavConfig,
                                api = KobblerApi(HttpClient(), stub.url, "token"),
                                formFactor = FormFactor.TV,
                                player = null,
                                gps = GlobalPlayerState(),
                            )
                        }
                    }
                }
                mainClock.advanceTimeBy(1_000)
                runOnIdle { nav.navigate(Destination.Tab.Movies) }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Categories").fetchSemanticsNodes().isNotEmpty() }
                test(queries)
            }
        }

    private fun DesktopComposeUiTest.press(key: Key) {
        // The page is its own window, the newest root; keys go to it, not the screen below.
        onAllNodes(isRoot()).onLast().performKeyInput { pressKey(key) }
        mainClock.advanceTimeBy(300)
        waitForIdle()
    }

    private fun DesktopComposeUiTest.focusedText(): String? =
        onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Focused, true))
            .fetchSemanticsNodes().lastOrNull()
            ?.config?.getOrElseNullable(SemanticsProperties.Text) { null }?.joinToString(" ")

    @Test
    fun `the remote walks the category tiles and picks one`() = moviesPage { queries ->
        onNodeWithText("Categories").performClick()
        waitUntil(timeoutMillis = 5_000) { focusedText()?.startsWith("All categories") == true }
        press(Key.DirectionRight)
        assertEquals("Action 4 titles", focusedText())
        press(Key.DirectionRight)
        assertEquals("Comedy 1 title", focusedText())
        press(Key.DirectionDown)
        assertEquals("Drama 7 titles", focusedText())
        press(Key.DirectionCenter)
        waitUntil(timeoutMillis = 5_000) { queries.any { "genre=Drama" in it } }
        assertTrue(onAllNodesWithText("Action").fetchSemanticsNodes().isEmpty(), "the page stayed open")
        assertTrue(onAllNodesWithText("Drama").fetchSemanticsNodes().isNotEmpty(), "the picked category is not shown")
    }

    @Test
    fun `back closes the page and keeps the category`() = moviesPage {
        onNodeWithText("Categories").performClick()
        waitUntil(timeoutMillis = 5_000) { focusedText()?.startsWith("All categories") == true }
        press(Key.Back)
        assertTrue(onAllNodesWithText("All categories").fetchSemanticsNodes().isEmpty(), "back left the page open")
        onNodeWithText("Categories").assertExists()
    }
}
