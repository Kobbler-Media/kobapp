package dev.kobbler.app.ui.dashboard

import dev.kobbler.app.runUiTest
import dev.kobbler.app.UiTimeoutMs
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.kobbler.app.data.AuthState
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.LocalAuthState
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.DataCache
import dev.kobbler.app.ui.player.StubServer
import dev.kobbler.kobfocus.FocusScopeHost
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * IPTV has the Live TV page's shape: tabs, a chip row, then one row per channel to scan and
 * pick from. Its library is tens of thousands of channels, so it opens on the favourites
 * and pages the rest in behind a chip.
 */
@OptIn(ExperimentalTestApi::class)
class IptvPageTest {

    private val now = Clock.System.now()

    private fun programme(id: String, title: String, from: Int, to: Int) =
        """{"id":"$id","channel_id":"i1","title":"$title","start_time":"${now + from.minutes}","stop_time":"${now + to.minutes}"}"""

    private val routes = mapOf<String, (com.sun.net.httpserver.HttpExchange) -> String>(
        "/api/v1/admin/iptv/epg/favourites" to { _ ->
            """{"grid":[{"channel":{"id":"i1","name":"Sky Sport"},"programs":[${programme("p1", "Derby Day", -10, 50)},${programme("p2", "Post Match", 50, 80)}]}]}"""
        },
        "/api/v1/admin/iptv/favourites" to { _ -> """[{"id":"f1","channel_id":"i1"}]""" },
        "/api/v1/admin/iptv/favourites/f1" to { _ -> "{}" },
        "/api/v1/admin/iptv/groups" to { _ -> """[{"id":"g1","name":"Sports","slug":"sports","channel_count":2}]""" },
        "/api/v1/admin/iptv/watches/matches" to { _ ->
            """[{"watch_id":"w1","channel_id":"i2","channel_name":"BBC One","epg_title":"Cup Final","start_time":"${now - 5.minutes}","stop_time":"${now + 90.minutes}","is_live":true}]"""
        },
        "/api/v1/admin/iptv/channels" to { _ ->
            """{"items":[{"id":"i1","name":"Sky Sport","category":"Sports"},{"id":"i2","name":"BBC One","category":"UK"}],"total":2}"""
        },
    )

    private class Outcome {
        val watched = mutableListOf<String>()
        val opened = mutableListOf<String>()
    }

    private val sizes = mapOf(FormFactor.Phone to (400 to 820), FormFactor.Desktop to (1280 to 800), FormFactor.TV to (1280 to 720))

    private fun page(formFactor: FormFactor, body: DesktopComposeUiTest.(Outcome, StubServer) -> Unit) {
        val (w, h) = sizes.getValue(formFactor)
        val outcome = Outcome()
        runUiTest(width = w, height = h) {
            DataCache.clear()
            StubServer(routes).use { stub ->
                val api = KobblerApi(HttpClient(), stub.url, "token")
                setContent {
                    val auth = AuthState(rememberCoroutineScope(), formFactor)
                    CompositionLocalProvider(LocalAuthState provides auth) {
                        KobappTheme(formFactor = formFactor) {
                            FocusScopeHost(id = "iptv") {
                                IptvScreen(
                                    api = api,
                                    formFactor = formFactor,
                                    onWatch = { outcome.watched += it.id },
                                    onChannelClick = { outcome.opened += it.id },
                                )
                            }
                        }
                    }
                }
                waitUntil(timeoutMillis = UiTimeoutMs) { count("Derby Day") > 0 }
                waitForIdle()
                body(outcome, stub)
            }
        }
    }

    private fun DesktopComposeUiTest.count(text: String) = onAllNodesWithText(text).fetchSemanticsNodes().size

    private fun everywhere(check: DesktopComposeUiTest.(Outcome, StubServer) -> Unit) {
        val failures = FormFactor.entries.mapNotNull { ff ->
            runCatching { page(ff) { outcome, stub -> check(outcome, stub) } }
                .exceptionOrNull()?.let { "$ff: ${it.message?.lineSequence()?.first()}" }
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun `the header is the tabs and no search field`() = everywhere { _, _ ->
        listOf("Guide", "Channels", "Recordings").forEach { assertTrue(count(it) > 0, "no '$it' tab") }
        assertTrue(onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty(), "IPTV still has a text field")
    }

    @Test
    fun `it opens on the favourites, saying what is on and what is next`() = everywhere { _, _ ->
        assertTrue(count("Post Match") > 0, "the favourite does not say what is next")
        assertEquals(1, count("BBC One"), "a channel that is not a favourite is listed before All is picked")
        onNodeWithText("All 2").performClick()
        waitUntil(timeoutMillis = UiTimeoutMs) { count("BBC One") == 2 }
    }

    @Test
    fun `picking a channel plays it`() = everywhere { outcome, _ ->
        onAllNodesWithText("Derby Day").onFirst().performClick()
        waitForIdle()
        assertEquals(listOf("i1"), outcome.watched)
    }

    @Test
    fun `a watched-for match that is live is offered first, ready to play`() = everywhere { outcome, _ ->
        onNodeWithText("Cup Final").performClick()
        waitForIdle()
        assertEquals(listOf("i2"), outcome.watched)
    }

    @Test
    fun `a channel's own page is a press away`() = everywhere { outcome, _ ->
        onNodeWithContentDescription("Sky Sport schedule").performClick()
        waitForIdle()
        assertEquals(listOf("i1"), outcome.opened)
    }

    /** The favourites feed carries no favourite id, so un-favouriting from it used to add it again. */
    @Test
    fun `un-favouriting a favourite removes it`() = everywhere { _, stub ->
        onNodeWithContentDescription("Remove favourite Sky Sport").performClick()
        waitUntil(timeoutMillis = UiTimeoutMs) { "/api/v1/admin/iptv/favourites/f1" in stub.requestBodies }
    }
}
