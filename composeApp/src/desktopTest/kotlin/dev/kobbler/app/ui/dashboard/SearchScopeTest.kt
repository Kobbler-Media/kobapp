package dev.kobbler.app.ui.dashboard

import dev.kobbler.app.runUiTest
import dev.kobbler.app.UiTimeoutMs
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import dev.kobbler.app.ui.navigation.Destination
import dev.kobbler.app.ui.navigation.navConfigFor
import dev.kobbler.app.ui.navigation.searchScopesFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Search opens scoped to the section it was opened from, and the scope is a chip in the search
 * bar whose menu widens it to everything or narrows it to one kind. Live TV is
 * a scope of its own: the guide and the channel list are searched there, and nowhere else
 * but All. Hits the library search returns for a type the app cannot open never surface as
 * a section of their own.
 */
@OptIn(ExperimentalTestApi::class)
class SearchScopeTest {

    private val now = Clock.System.now()

    private val routes = mapOf<String, (com.sun.net.httpserver.HttpExchange) -> String>(
        "/api/v1/admin/search" to { _ ->
            """{"top_hits":[{"type":"movie","id":"m1","title":"Blockbuster"},{"type":"tv_show","id":"s1","title":"Blockhead","kind":"tv"}],"remaining":[{"type":"tv_show","id":"sp1","title":"Block League","kind":"sport"},{"type":"iptv_channel","id":"i5","title":"Block Sports HD"},{"type":"setting","id":"st1","title":"Blocklist settings"}]}"""
        },
        "/api/v1/admin/livetv/guide" to { _ ->
            """{"channels":[{"channel":{"id":"c9","lcn":9,"name":"Nine"},"entries":[{"id":"g1","channel_id":"c9","start_time":"${now + 2.hours}","end_time":"${now + 3.hours}","title":"The Block"}]}]}"""
        },
        "/api/v1/admin/livetv/channels" to { _ ->
            """{"items":[{"id":"c7","lcn":7,"name":"Blocktown TV"}],"total":1}"""
        },
        "/api/v1/admin/iptv/channels" to { _ ->
            """{"items":[{"id":"i5","name":"Block Sports HD","category":"Sports"}],"total":1}"""
        },
    )

    private class Case(
        val name: String,
        val start: SearchScope,
        val scopes: List<SearchScope> = SearchScope.entries,
        val shown: List<String>,
        val hidden: List<String>,
        val pick: SearchScope? = null,
    )

    private val cases = listOf(
        Case("live tv", SearchScope.LiveTv, shown = listOf("The Block", "Blocktown TV"), hidden = listOf("Blockbuster", "Blockhead", "Block Sports HD")),
        Case("iptv", SearchScope.Iptv, shown = listOf("Block Sports HD"), hidden = listOf("Blockbuster", "The Block", "Blocktown TV")),
        Case("movies", SearchScope.Movies, shown = listOf("Blockbuster"), hidden = listOf("Blockhead", "The Block", "Blocktown TV")),
        Case("tv", SearchScope.Tv, shown = listOf("Blockhead"), hidden = listOf("Blockbuster", "The Block", "Block League")),
        Case("sports", SearchScope.Sports, shown = listOf("Block League"), hidden = listOf("Blockhead", "Blockbuster", "The Block", "Block Sports HD")),
        Case("live tv widened", SearchScope.LiveTv, shown = listOf("The Block", "Blocktown TV", "Blockbuster", "Blockhead", "Block Sports HD"), hidden = listOf("Iptv_channel", "Blocklist settings"), pick = SearchScope.All),
        Case("all narrowed to tv", SearchScope.All, shown = listOf("Blockhead"), hidden = listOf("Blockbuster", "The Block", "Block Sports HD"), pick = SearchScope.Tv),
        Case("sports widened", SearchScope.Sports, shown = listOf("Block League", "Blockhead", "Blockbuster"), hidden = listOf("Blocklist settings"), pick = SearchScope.All),
        Case(
            "no live tv",
            SearchScope.All,
            scopes = SearchScope.entries - SearchScope.LiveTv - SearchScope.Iptv,
            shown = listOf("Blockbuster", "Blockhead"),
            hidden = listOf("The Block", "Blocktown TV", "Live TV", "Block Sports HD", "IPTV"),
        ),
    )

    @Test
    fun `sports opens its own scope, offered only where the sports tab is`() {
        assertEquals(SearchScope.Sports, SearchScope.forTab(Destination.Tab.Sports))
        assertTrue(SearchScope.Sports in searchScopesFor(navConfigFor(isKids = false)))
        assertTrue(SearchScope.Sports !in searchScopesFor(navConfigFor(isKids = true)))
    }

    @Test
    fun `search is scoped to where it was opened and widens to everything`() {
        val failures = FormFactor.entries.flatMap { ff ->
            cases.mapNotNull { case ->
                runCatching { check(case, ff) }.exceptionOrNull()?.let { "${ff.name}/${case.name}: ${it.message?.lineSequence()?.first()}" }
            }
        }
        assertEquals(emptyList(), failures)
    }

    private fun check(case: Case, formFactor: FormFactor) = runUiTest(width = 1280, height = 900) {
        StubServer(routes).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            setContent {
                KobappTheme(formFactor = formFactor) {
                    var scope by remember { mutableStateOf(case.start) }
                    SearchOverlay(
                        api = api,
                        scope = scope,
                        scopes = case.scopes,
                        onScopeChange = { scope = it },
                        query = "block",
                        onQueryChange = {},
                        onDismiss = {},
                        onResultClick = {},
                        formFactor = formFactor,
                        autoFocusQuery = false,
                    )
                }
            }
            case.pick?.let { pickScope(it) }
            waitUntil(timeoutMillis = UiTimeoutMs) { case.shown.all { has(it) } }
            mainClock.advanceTimeBy(1_000)
            waitForIdle()
            val leaked = case.hidden.filter { has(it) }
            assertEquals(emptyList(), leaked, "shown outside their scope")
        }
    }

    private fun DesktopComposeUiTest.has(text: String) = onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
}
