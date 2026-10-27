package dev.kobbler.app.ui.dashboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.kobbler.app.UiTimeoutMs
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.runUiTest
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The scope lives in one chip at the end of the search field, so the jump chips below get
 * the whole row. It is there before anything is typed, and on a remote it is a stop to the
 * right of the field that never takes the field's entry focus.
 */
@OptIn(ExperimentalTestApi::class)
class SearchScopeChipTest {

    private val library = (1..6).flatMap { n ->
        listOf("""{"type":"movie","id":"m$n","title":"Blockbuster $n"}""", """{"type":"book","id":"b$n","title":"Blockchain $n"}""")
    }.joinToString(",", prefix = """{"top_hits":[""", postfix = """],"remaining":[]}""")

    private val routes = mapOf<String, (com.sun.net.httpserver.HttpExchange) -> String>(
        "/api/v1/admin/search" to { _ -> library },
        "/api/v1/admin/video-metadata/search/movies" to { _ ->
            (1..6).joinToString(",", prefix = """{"results":[""", postfix = "]}") { """{"external_id":"d$it","title":"Faraway $it"}""" }
        },
        "/api/v1/admin/video-metadata/search/tv" to { _ -> """{"results":[]}""" },
    )

    private val withoutLive = SearchScope.entries - SearchScope.LiveTv - SearchScope.Iptv

    private fun eachFormFactor(check: (FormFactor) -> Unit) {
        val failures = FormFactor.entries.mapNotNull { ff ->
            runCatching { check(ff) }.exceptionOrNull()?.let { "${ff.name}: ${it.message?.lineSequence()?.first()}" }
        }
        assertEquals(emptyList(), failures)
    }

    private fun DesktopComposeUiTest.showSearch(
        stub: StubServer,
        formFactor: FormFactor,
        query: String,
        scopes: List<SearchScope>,
        autoFocusQuery: Boolean = false,
    ): () -> FocusManager {
        var manager: FocusManager? = null
        val api = KobblerApi(HttpClient(), stub.url, "token")
        setContent {
            KobappTheme(formFactor = formFactor) {
                manager = LocalFocusManager.current
                var scope by remember { mutableStateOf(SearchScope.All) }
                SearchOverlay(
                    api = api,
                    scope = scope,
                    scopes = scopes,
                    onScopeChange = { scope = it },
                    query = query,
                    onQueryChange = {},
                    onDismiss = {},
                    onResultClick = {},
                    formFactor = formFactor,
                    autoFocusQuery = autoFocusQuery,
                )
            }
        }
        waitForIdle()
        return { manager!! }
    }

    @Test
    fun `the chip names the scope and its menu offers exactly the given scopes`() = eachFormFactor { ff ->
        listOf(SearchScope.entries, withoutLive).forEach { scopes ->
            runUiTest(width = 1280, height = 900) {
                StubServer(routes).use { stub ->
                    showSearch(stub, ff, query = "", scopes = scopes)
                    scopeChip().assertTextEquals(SearchScope.All.label)
                    assertTrue(onAllNodesWithText(SearchScope.Books.label).fetchSemanticsNodes().isEmpty(), "a scope row is still on screen")
                    scopeChip().performClick()
                    waitForIdle()
                    assertEquals(scopes.map { it.label }, scopeOptions())
                    scopeOption(SearchScope.Books).performClick()
                    waitForIdle()
                    scopeChip().assertTextEquals(SearchScope.Books.label)
                    assertTrue(onAllNodes(hasAnyAncestor(isPopup())).fetchSemanticsNodes().isEmpty(), "the menu stayed open")
                }
            }
        }
    }

    @Test
    fun `jump chips start where the bar starts, not after the scope`() = eachFormFactor { ff ->
        runUiTest(width = 1280, height = 900) {
            StubServer(routes).use { stub ->
                showSearch(stub, ff, query = "block", scopes = withoutLive)
                waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithTag(SearchJumpChipsTag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
                val jump = onNodeWithTag(SearchJumpChipsTag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                val field = onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
                assertTrue(jump.left < field.left, "jump chip at ${jump.left} is not left of the field at ${field.left}")
            }
        }
    }

    @Test
    fun `on tv the chip is a stop right of the field and keeps focus after a pick`() {
        val failures = listOf("", "block").mapNotNull { query ->
            runCatching {
                runUiTest(width = 1280, height = 900) {
                    StubServer(routes).use { stub ->
                        val manager = showSearch(stub, FormFactor.TV, query = query, scopes = SearchScope.entries, autoFocusQuery = true)
                        waitUntil("the query field takes focus", timeoutMillis = UiTimeoutMs) { onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().isNotEmpty() }
                        scopeChip().assertIsNotFocused()
                        repeat(if (query.isEmpty()) 1 else 2) {
                            runOnIdle { manager().moveFocus(FocusDirection.Right) }
                            waitForIdle()
                        }
                        scopeChip().assertIsFocused()
                        scopeChip().performClick()
                        waitUntil("the menu takes focus", timeoutMillis = UiTimeoutMs) {
                            onAllNodes(hasAnyAncestor(isPopup()) and isFocused()).fetchSemanticsNodes().isNotEmpty()
                        }
                        scopeOption(SearchScope.Tv).performClick()
                        waitForIdle()
                        scopeChip().assertTextEquals(SearchScope.Tv.label)
                        scopeChip().assertIsFocused()
                    }
                }
            }.exceptionOrNull()?.let { "query '$query': ${it.message?.lineSequence()?.first()}" }
        }
        assertEquals(emptyList(), failures)
    }
}
