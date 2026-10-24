package dev.kobbler.app.ui.dashboard

import dev.kobbler.app.runUiTest
import dev.kobbler.app.UiTimeoutMs
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.performSemanticsAction
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.DataCache
import dev.kobbler.app.ui.player.StubServer
import dev.kobbler.kobfocus.FocusScopeHost
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Browse pages carry Home's backdrop: on a pointer or remote it follows the card being
 * pointed at; on touch it opens on the first row and moves to the bottom row a scroll
 * comes to rest on.
 */
@OptIn(ExperimentalTestApi::class)
class BrowseBackdropTest {

    private fun movies(count: Int) = buildString {
        append("""{"items":[""")
        append((1..count).joinToString(",") {
            """{"id":"g$it","title":"Grid $it","year":2020,"poster_url":"/img/g$it-poster","backdrop_url":"/img/g$it-backdrop"}"""
        })
        append("""],"total_count":$count}""")
    }

    private fun shows(count: Int) = buildString {
        append("""{"items":[""")
        append((1..count).joinToString(",") {
            """{"id":"s$it","title":"Show $it","year":2020,"poster_url":"/img/s$it-poster","backdrop_url":"/img/s$it-backdrop"}"""
        })
        append("""],"total_count":$count}""")
    }

    private class Page(val stub: StubServer) {
        fun requested(path: String) = path in stub.requestBodies.keys
        fun requestedAny(paths: Collection<String>) = stub.requestBodies.keys.any { it in paths }
    }

    private fun browse(
        formFactor: FormFactor,
        tv: Boolean,
        width: Int = 1280,
        height: Int = 800,
        test: DesktopComposeUiTest.(Page) -> Unit,
    ) = runUiTest(width = width, height = height) {
        DataCache.clear()
        StubServer(
            mapOf(
                "/api/v1/admin/movies" to { _ -> movies(60) },
                "/api/v1/admin/tv/shows" to { _ -> shows(60) },
                "/api/v1/admin/discover" to { _ -> """{"watch_next":[],"recently_added":[],"sections":[]}""" },
                "/api/v1/admin/dashboard" to { _ -> "{}" },
            ),
        ).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            setContent {
                KobappTheme(formFactor = formFactor) {
                    FocusScopeHost(id = "browse") {
                        if (tv) TvShowsScreen(api = api, formFactor = formFactor, onShowClick = {})
                        else MoviesScreen(api = api, formFactor = formFactor, onMovieClick = {})
                    }
                }
            }
            val first = if (tv) "Show 1" else "Grid 1"
            waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithText(first).fetchSemanticsNodes().isNotEmpty() }
            waitForIdle()
            val page = Page(stub)
            waitUntil(timeoutMillis = UiTimeoutMs) { page.requested("/img/${if (tv) "s" else "g"}1-poster") }
            test(page)
        }
    }

    private fun DesktopComposeUiTest.settle() {
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
    }

    // The swap waits on virtual time, which waitUntil alone does not move.
    private fun DesktopComposeUiTest.awaitClock(condition: () -> Boolean) {
        repeat(50) {
            if (condition()) return
            settle()
            Thread.sleep(100)
        }
        assertTrue(condition(), "condition never held")
    }

    @Test
    fun `focus and hover move the backdrop on a wide screen`() {
        for (tv in listOf(false, true)) {
            val p = if (tv) "s" else "g"
            val title = if (tv) "Show" else "Grid"
            browse(FormFactor.Desktop, tv) { page ->
                settle()
                waitUntil(timeoutMillis = UiTimeoutMs) { page.requested("/img/${p}1-backdrop") }

                onAllNodesWithText("$title 3").onFirst().performSemanticsAction(SemanticsActions.RequestFocus)
                settle()
                waitUntil(timeoutMillis = UiTimeoutMs) { page.requested("/img/${p}3-backdrop") }

                onAllNodesWithText("$title 2").onFirst().performMouseInput { moveTo(center) }
                settle()
                waitUntil(timeoutMillis = UiTimeoutMs) { page.requested("/img/${p}2-backdrop") }
            }
        }
    }

    @Test
    fun `a touch scroll moves the backdrop to the row it stops on`() {
        for (tv in listOf(false, true)) {
            val p = if (tv) "s" else "g"
            browse(FormFactor.Phone, tv, width = 400, height = 800) { page ->
                settle()
                waitUntil(timeoutMillis = UiTimeoutMs) { page.requested("/img/${p}1-backdrop") }
                val others = (2..60).map { "/img/$p$it-backdrop" }
                assertTrue(!page.requestedAny(others), "the backdrop moved before any scroll")

                repeat(3) {
                    onAllNodes(hasScrollToIndexAction()).onFirst().performTouchInput { swipeUp() }
                    settle()
                }
                awaitClock { page.requestedAny(others) }
            }
        }
    }
}
