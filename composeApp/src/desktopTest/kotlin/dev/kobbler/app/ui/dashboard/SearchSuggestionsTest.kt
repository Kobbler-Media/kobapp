package dev.kobbler.app.ui.dashboard

import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.platform.LocalFocusManager
import dev.kobbler.app.UiTimeoutMs
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.UserSettings
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.runUiTest
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Typing on a TV remote is slow, so search offers whole titles to pick, the way a keyboard's
 * suggestion strip does: before anything is typed, what is trending on TMDB; while typing,
 * titles, authors, channels and guide programmes that the query starts a word of. A TV hands
 * them to its keyboard's own suggestion row; a phone keyboard takes none from apps, so the
 * strip rides on top of it and goes with it; desktop keeps it under the field. It is never
 * part of the results, and picking one searches for it.
 */
@OptIn(ExperimentalTestApi::class)
class SearchSuggestionsTest {

    private val requests = CopyOnWriteArrayList<String>()

    private val routes = mapOf<String, (com.sun.net.httpserver.HttpExchange) -> String>(
        "/api/v1/admin/search/suggestions" to { exchange ->
            val query = exchange.requestURI.query.orEmpty()
            requests += query
            if (query.startsWith("q=&")) {
                """{"suggestions":[{"title":"The Last of Us","media_type":"tv","source":"tmdb"}]}"""
            } else {
                """{"suggestions":[{"title":"Breaking Chaos","media_type":"book","source":"library"},{"title":"Breaking Bad","media_type":"tv","source":"tmdb"},{"title":"Breakin'","media_type":"movie","source":"library"}]}"""
            }
        },
        "/api/v1/admin/search" to { _ -> """{"top_hits":[{"type":"movie","id":"m1","title":"Jailbreak"}],"remaining":[]}""" },
    )

    private fun overlay(
        query: String,
        scope: SearchScope,
        picked: MutableList<String>,
        formFactor: FormFactor = FormFactor.Desktop,
        keyboardShown: Boolean = false,
        autoFocusQuery: Boolean = false,
        onFocusManager: (FocusManager) -> Unit = {},
        test: suspend DesktopComposeUiTest.() -> Unit,
    ) = runUiTest(width = 1280, height = 900) {
        UserSettings.remove("search_recents")
        requests.clear()
        StubServer(routes).use { stub ->
            setContent {
                KobappTheme(formFactor = formFactor) {
                    onFocusManager(LocalFocusManager.current)
                    SearchOverlay(
                        api = KobblerApi(HttpClient(), stub.url, "token"),
                        scope = scope,
                        onScopeChange = {},
                        query = query,
                        onQueryChange = { picked += it },
                        onDismiss = {},
                        onResultClick = {},
                        formFactor = formFactor,
                        keyboardShown = keyboardShown,
                        autoFocusQuery = autoFocusQuery,
                    )
                }
            }
            test()
        }
    }

    private fun DesktopComposeUiTest.awaitText(text: String) =
        waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun DesktopComposeUiTest.bounds(tag: String): Rect = onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun DesktopComposeUiTest.count(matcher: SemanticsMatcher) =
        onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun DesktopComposeUiTest.assertInStripOnly(title: String) {
        assertEquals(1, count(hasText(title) and hasAnyAncestor(hasTestTag(SearchSuggestionStripTag))), "$title in the strip")
        assertEquals(0, count(hasText(title) and hasAnyAncestor(hasTestTag(SearchResultsTag))), "$title in the results")
    }

    @Test
    fun `the strip sits on the field on desktop, on the keyboard on a phone, and in the keyboard on a TV`() {
        val cases = listOf(
            Triple(FormFactor.Desktop, false, "field"),
            Triple(FormFactor.Phone, true, "keyboard"),
            Triple(FormFactor.Phone, false, null),
            Triple(FormFactor.TV, true, null),
            Triple(FormFactor.TV, false, null),
        )
        val failures = cases.mapNotNull { (ff, keyboard, where) ->
            runCatching {
                overlay("break", SearchScope.All, mutableListOf(), formFactor = ff, keyboardShown = keyboard) {
                    awaitText("Jailbreak")
                    waitUntil(timeoutMillis = UiTimeoutMs) { requests.isNotEmpty() }
                    waitForIdle()
                    if (where == null) {
                        assertEquals(0, count(hasTestTag(SearchSuggestionStripTag)), "a strip is showing")
                        assertEquals(0, count(hasText("Breaking Bad")), "a suggestion is showing")
                        return@overlay
                    }
                    awaitText("Breaking Bad")
                    assertInStripOnly("Breaking Bad")
                    assertInStripOnly("Breakin'")
                    val field = bounds(SearchFieldTag)
                    val strip = bounds(SearchSuggestionStripTag)
                    val results = bounds(SearchResultsTag)
                    if (where == "keyboard") {
                        assertTrue(strip.top >= results.bottom, "strip $strip is not under the results $results")
                    } else {
                        assertTrue(strip.top >= field.bottom && strip.bottom <= results.top, "strip $strip is not between field $field and results $results")
                    }
                }
            }.exceptionOrNull()?.let { "$ff keyboard=$keyboard: ${it.message?.lineSequence()?.first()}" }
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun `shows and movies are suggested before anything else`() {
        overlay("break", SearchScope.All, mutableListOf()) {
            awaitText("Breaking Chaos")
            val order = listOf("Breaking Bad", "Breakin'", "Breaking Chaos").map { title ->
                onAllNodes(hasText(title) and hasAnyAncestor(hasTestTag(SearchSuggestionStripTag)), useUnmergedTree = true)
                    .fetchSemanticsNodes().single().boundsInRoot.left
            }
            assertEquals(order.sorted(), order, "strip order by x: $order")
        }
    }

    @Test
    fun `an empty query offers trending titles in the strip`() {
        val picked = mutableListOf<String>()
        overlay("", SearchScope.All, picked) {
            awaitText("The Last of Us")
            assertInStripOnly("The Last of Us")
            onNodeWithText("The Last of Us").performClick()
            waitForIdle()
        }
        assertEquals(listOf("The Last of Us"), picked)
        assertEquals("q=&media=movie,tv,book&limit=8", requests.single())
    }

    @Test
    fun `the arrow keys walk from the field through the strip to the results`() {
        var focus: FocusManager? = null
        overlay("break", SearchScope.All, mutableListOf(), autoFocusQuery = true, onFocusManager = { focus = it }) {
            awaitText("Breaking Bad")
            awaitText("Jailbreak")
            waitUntil(timeoutMillis = UiTimeoutMs) { count(hasTestTag(SearchFieldTag) and isFocused()) == 1 }
            onNodeWithTag(SearchFieldTag, useUnmergedTree = true).performKeyInput { pressKey(Key.DirectionDown) }
            waitUntil(timeoutMillis = UiTimeoutMs) {
                count(isFocused() and hasAnyAncestor(hasTestTag(SearchSuggestionStripTag))) == 1
            }
            runOnIdle { focus!!.moveFocus(FocusDirection.Down) }
            waitUntil(timeoutMillis = UiTimeoutMs) {
                count(isFocused() and hasAnyAncestor(hasTestTag(SearchResultsTag))) == 1
            }
            runOnIdle { focus!!.moveFocus(FocusDirection.Up) }
            waitUntil(timeoutMillis = UiTimeoutMs) {
                count(isFocused() and hasAnyAncestor(hasTestTag(SearchSuggestionStripTag))) == 1
            }
        }
    }

    @Test
    fun `every scope asks for suggestions of what it searches, and picking one fills the query`() {
        val expected = mapOf(
            SearchScope.All to "movie,tv,book",
            SearchScope.Movies to "movie",
            SearchScope.Tv to "tv",
            SearchScope.Books to "book",
            SearchScope.LiveTv to "livetv",
            SearchScope.Iptv to "iptv",
            SearchScope.Sports to "sports",
        )
        assertEquals(SearchScope.entries.toSet(), expected.keys)
        expected.forEach { (scope, media) ->
            val picked = mutableListOf<String>()
            overlay("break", scope, picked) {
                awaitText("Breaking Bad")
                onNodeWithText("Breaking Bad").performClick()
                waitForIdle()
            }
            assertEquals(listOf("Breaking Bad"), picked, "$scope")
            assertTrue(requests.isNotEmpty() && requests.all { it == "q=break&media=$media&limit=8" }, "$scope requests: $requests")
        }
    }
}
