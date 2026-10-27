package dev.kobbler.app.ui.dashboard

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.kobbler.app.UiTimeoutMs
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.runUiTest
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where results sit as they arrive. Every source answers on its own clock, and a list that
 * moves under the viewer while they read it is worse than one that is slow.
 */
@OptIn(ExperimentalTestApi::class)
class SearchResultsLayoutTest {

    private fun libraryHits(vararg groups: Pair<String, String>, each: Int) = groups.flatMap { (type, title) ->
        (1..each).map { """{"type":"$type","id":"$type$it","title":"$title $it"}""" }
    }.joinToString(",", prefix = """{"top_hits":[""", postfix = """],"remaining":[]}""")

    private val discoverMovies = (1..6).joinToString(",", prefix = """{"results":[""", postfix = "]}") {
        """{"external_id":"d$it","title":"Faraway $it","year":${2000 + it}}"""
    }

    private val longLibrary = libraryHits("movie" to "Blockbuster", "person" to "Blocky", "book" to "Blockchain", "author" to "Bloch", each = 6)

    private val libraryServed = AtomicBoolean(false)

    private fun routes(library: String, libraryDelayMs: Long = 0) = mapOf<String, (com.sun.net.httpserver.HttpExchange) -> String>(
        "/api/v1/admin/search" to { _ -> Thread.sleep(libraryDelayMs); libraryServed.set(true); library },
        "/api/v1/admin/video-metadata/search/movies" to { _ -> discoverMovies },
        "/api/v1/admin/video-metadata/search/tv" to { _ -> """{"results":[]}""" },
    )

    private fun eachFormFactor(check: (FormFactor) -> Unit) {
        val failures = FormFactor.entries.mapNotNull { ff ->
            runCatching { check(ff) }.exceptionOrNull()?.let { "${ff.name}: ${it.message?.lineSequence()?.first()}" }
        }
        assertEquals(emptyList(), failures)
    }

    private fun DesktopComposeUiTest.showSearch(stub: StubServer, formFactor: FormFactor) {
        libraryServed.set(false)
        val api = KobblerApi(HttpClient(), stub.url, "token")
        setContent {
            KobappTheme(formFactor = formFactor) {
                SearchOverlay(
                    api = api,
                    scope = SearchScope.All,
                    scopes = SearchScope.entries - SearchScope.LiveTv - SearchScope.Iptv,
                    onScopeChange = {},
                    query = "block",
                    onQueryChange = {},
                    onDismiss = {},
                    onResultClick = {},
                    formFactor = formFactor,
                    autoFocusQuery = false,
                )
            }
        }
    }

    @Test
    fun `a library that answers after discover still opens on its own first row`() = eachFormFactor { ff ->
        runUiTest(width = 1280, height = 900) {
            StubServer(routes(longLibrary, libraryDelayMs = 1_500)).use { stub ->
                showSearch(stub, ff)
                waitUntil(timeoutMillis = UiTimeoutMs) { has("Faraway 1") }
                waitUntil(timeoutMillis = UiTimeoutMs) { libraryServed.get() }
                mainClock.advanceTimeBy(1_000)
                waitForIdle()
                onNodeWithText("Blockbuster 1").assertIsDisplayed()
                val library = onNodeWithText("Blockbuster 1").fetchSemanticsNode().boundsInRoot.top
                val discover = onAllNodesWithText("Faraway 1").fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.top
                assertTrue(discover == null || library < discover, "library at $library is not above discover at $discover")
            }
        }
    }

    @Test
    fun `the best of each section leads, and the rest of discover is one press away`() = eachFormFactor { ff ->
        runUiTest(width = 1280, height = 900) {
            StubServer(routes(longLibrary)).use { stub ->
                showSearch(stub, ff)
                waitUntil(timeoutMillis = UiTimeoutMs) { has("Faraway 1") && has("Discover · Movies") }
                val tops = listOf("Blockbuster 1", "Blocky 1", "Blockchain 1", "Bloch 1", "Faraway 1")
                    .map { onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top }
                assertTrue(tops.zipWithNext().all { (a, b) -> a <= b }, "top results out of order: $tops")
                assertTrue(tops.last() < onNodeWithText("Blockbuster 2").fetchSemanticsNode().boundsInRoot.top, "top results are not above the sections")
                assertTrue(!has("Faraway 2"), "the rest of discover was already on screen, so the jump proves nothing")
                onAllNodesWithText("Discover · Movies").onFirst().performClick()
                waitUntil(timeoutMillis = UiTimeoutMs) { has("Faraway 2") }
                onNodeWithText("Faraway 2", substring = true).assertIsDisplayed()
                if (ff == FormFactor.TV) {
                    waitUntil(timeoutMillis = UiTimeoutMs) {
                        onAllNodes(hasText("Faraway 2", substring = true) and isFocused()).fetchSemanticsNodes().isNotEmpty()
                    }
                }
            }
        }
    }

    @Test
    fun `tv reads results two to a row, everything else one`() = eachFormFactor { ff ->
        runUiTest(width = 1280, height = 900) {
            StubServer(routes(libraryHits("movie" to "Blockbuster", each = 4))).use { stub ->
                showSearch(stub, ff)
                waitUntil(timeoutMillis = UiTimeoutMs) { has("Blockbuster 3") }
                val first = onNodeWithText("Blockbuster 2").fetchSemanticsNode().boundsInRoot
                val second = onNodeWithText("Blockbuster 3").fetchSemanticsNode().boundsInRoot
                val sideBySide = first.top == second.top && second.left > first.right
                assertEquals(ff == FormFactor.TV, sideBySide, "first=$first second=$second")
            }
        }
    }

    private fun DesktopComposeUiTest.has(text: String) = onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
}
