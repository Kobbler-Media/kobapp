package dev.kobbler.app.ui.dashboard.livetv

import dev.kobbler.app.runUiTest
import dev.kobbler.app.UiTimeoutMs
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.CompositionLocalProvider
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
import dev.kobbler.app.data.UserSettings
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.DataCache
import dev.kobbler.app.ui.dashboard.LiveTvScreen
import dev.kobbler.app.ui.dashboard.LiveTvTabKey
import dev.kobbler.app.ui.player.StubServer
import dev.kobbler.kobfocus.FocusScopeHost
import io.ktor.client.HttpClient
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The Live TV page is for scanning the channels and picking one: one row per channel saying
 * what is on and what is next, the same on every screen. Later times are a picker away, and
 * what is usually watched around now is offered first.
 */
@OptIn(ExperimentalTestApi::class)
class LiveTvPageTest {

    private val now = Clock.System.now()

    private fun channel(id: String, name: String, lcn: Int) = """{"id":"$id","lcn":$lcn,"name":"$name"}"""
    private val seven = channel("c1", "Seven", 7)
    private val nine = channel("c2", "Nine", 9)

    private fun entry(id: String, channel: String, title: String, start: Instant, end: Instant) =
        """{"id":"$id","channel_id":"$channel","start_time":"$start","end_time":"$end","title":"$title"}"""

    private fun guideAt(from: Instant): String = if (from - now < 1.hours) {
        """{"channels":[
            {"channel":$seven,"entries":[${entry("g1", "c1", "Morning News", now - 10.minutes, now + 20.minutes)},${entry("g2", "c1", "Midday Movie", now + 20.minutes, now + 2.hours)}]},
            {"channel":$nine,"entries":[${entry("g3", "c2", "Kids Show", now - 5.minutes, now + 25.minutes)},${entry("g4", "c2", "Quiz Hour", now + 25.minutes, now + 85.minutes)}]}
        ]}"""
    } else {
        """{"channels":[
            {"channel":$seven,"entries":[${entry("g5", "c1", "Late Film", from - 10.minutes, from + 100.minutes)},${entry("g6", "c1", "Night News", from + 100.minutes, from + 130.minutes)}]},
            {"channel":$nine,"entries":[${entry("g7", "c2", "Doco", from, from + 60.minutes)}]}
        ]}"""
    }

    private val routes = mapOf<String, (com.sun.net.httpserver.HttpExchange) -> String>(
        "/api/v1/admin/livetv/tuners" to { _ -> """{"items":[]}""" },
        "/api/v1/admin/livetv/channels" to { _ -> """{"items":[$seven,$nine],"total":2}""" },
        "/api/v1/admin/livetv/guide" to { ex ->
            val from = Regex("from=([^&]+)").find(ex.requestURI.rawQuery.orEmpty())?.groupValues?.get(1)
                ?.let { Instant.parse(java.net.URLDecoder.decode(it, "UTF-8")) } ?: now
            guideAt(from)
        },
        "/api/v1/admin/livetv/on-now" to { _ ->
            """{"generated_at":"$now","groups":[
                {"key":"usual","title":"Your usual","subtitle":"","items":[{"channel":$nine,"guide":${entry("q1", "c2", "The Block", now + 8.minutes, now + 68.minutes)},"starts_in_minutes":8,"reason":"you usually watch this around now"}]},
                {"key":"series","title":"Because you watch it","subtitle":"","items":[{"channel":$seven,"guide":${entry("g1", "c1", "Morning News", now - 10.minutes, now + 20.minutes)},"minutes_left":20,"reason":"r"}]}
            ]}"""
        },
        "/api/v1/admin/livetv/recordings" to { _ -> """{"id":"rec1","title":"Late Film","status":"scheduled","items":[],"total":0}""" },
    )

    private class Outcome {
        val watched = mutableListOf<String>()
        val opened = mutableListOf<String>()
    }

    private val sizes = mapOf(FormFactor.Phone to (400 to 820), FormFactor.Desktop to (1280 to 800), FormFactor.TV to (1280 to 720))

    private fun page(formFactor: FormFactor, tab: String? = "Guide", body: DesktopComposeUiTest.(Outcome, StubServer) -> Unit) {
        val (w, h) = sizes.getValue(formFactor)
        val outcome = Outcome()
        runUiTest(width = w, height = h) {
            DataCache.clear()
            UserSettings.remove(LiveTvTabKey)
            StubServer(routes).use { stub ->
                val api = KobblerApi(HttpClient(), stub.url, "token")
                setContent {
                    val auth = AuthState(rememberCoroutineScope(), formFactor)
                    CompositionLocalProvider(LocalAuthState provides auth) {
                        KobappTheme(formFactor = formFactor) {
                            FocusScopeHost(id = "livetv") {
                                LiveTvScreen(
                                    api = api,
                                    formFactor = formFactor,
                                    onWatch = { outcome.watched += it.id },
                                    onChannelClick = { outcome.opened += it },
                                )
                            }
                        }
                    }
                }
                waitUntil(timeoutMillis = UiTimeoutMs) { has("Morning News") }
                waitForIdle()
                if (tab != null) {
                    onNodeWithText(tab).performClick()
                    waitUntil(timeoutMillis = UiTimeoutMs) { has("Midday Movie") || tab != "Guide" }
                    waitForIdle()
                }
                body(outcome, stub)
            }
        }
    }

    private fun DesktopComposeUiTest.has(text: String) = onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun everywhere(check: DesktopComposeUiTest.(FormFactor, Outcome, StubServer) -> Unit) {
        val failures = FormFactor.entries.mapNotNull { ff ->
            runCatching { page(ff) { outcome, stub -> check(ff, outcome, stub) } }
                .exceptionOrNull()?.let { "$ff: ${it.message?.lineSequence()?.first()}" }
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun `the header is the four tabs and no search field`() = everywhere { _, _, _ ->
        listOf("On now", "Guide", "Channels", "Recordings").forEach { assertTrue(has(it), "no '$it' tab") }
        assertTrue(onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty(), "Live TV still has a text field")
    }

    @Test
    fun `each channel says what is on and what is next`() = everywhere { _, _, _ ->
        listOf("Seven", "Morning News", "Midday Movie", "Nine", "Kids Show", "Quiz Hour").forEach {
            assertTrue(has(it), "'$it' is not in the guide")
        }
    }

    @Test
    fun `picking what is on starts watching it`() = everywhere { _, outcome, _ ->
        onAllNodesWithText("Kids Show").onFirst().performClick()
        waitForIdle()
        assertEquals(listOf("c2"), outcome.watched)
    }

    @Test
    fun `what is usually watched around now is offered first, ready to play`() = everywhere { _, outcome, _ ->
        waitUntil(timeoutMillis = UiTimeoutMs) { has("The Block") }
        assertTrue(has("in 8 min"), "the usual show does not say when it starts")
        onNodeWithText("The Block").performClick()
        waitForIdle()
        assertEquals(listOf("c2"), outcome.watched)
    }

    @Test
    fun `a later time is picked, and what is on then can be recorded or opened`() = everywhere { _, outcome, stub ->
        onNodeWithContentDescription("Pick a time").performClick()
        waitForIdle()
        onNodeWithText("Tomorrow").performClick()
        onNodeWithText("20:00").performClick()
        waitUntil(timeoutMillis = UiTimeoutMs) { has("Late Film") }
        assertTrue(has("Night News"), "what follows the picked time is missing")

        onNodeWithContentDescription("Record Late Film").performClick()
        waitUntil(timeoutMillis = UiTimeoutMs) { stub.requestBodies["/api/v1/admin/livetv/recordings"]?.contains("g5") == true }

        onNodeWithText("Late Film").performClick()
        waitForIdle()
        assertEquals(emptyList(), outcome.watched, "a programme that has not started was played")
        assertEquals(listOf("c1"), outcome.opened)
    }

    @Test
    fun `it opens on on now when there is something to suggest`() {
        val failures = FormFactor.entries.mapNotNull { ff ->
            runCatching {
                page(ff, tab = null) { _, _ ->
                    assertTrue(has("Because you watch it"), "the page did not open on On now")
                    assertTrue(!has("Midday Movie"), "the page opened on the guide")
                }
            }.exceptionOrNull()?.let { "$ff: ${it.message?.lineSequence()?.first()}" }
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun `on now is a tab of the page`() = everywhere { _, _, _ ->
        onNodeWithText("On now").performClick()
        waitUntil(timeoutMillis = UiTimeoutMs) { has("Because you watch it") }
    }

    /** A number lane left a hole beside a one-digit number; it is a chip on the logo instead. */
    @Test
    fun `the channel number sits on the logo, once`() = everywhere { _, _, _ ->
        for (tab in listOf("Guide", "Channels")) {
            onNodeWithText(tab).performClick()
            waitUntil(timeoutMillis = UiTimeoutMs) { has("Seven") }
            waitForIdle()
            val numbers = onAllNodesWithText("7", useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
            val maxGap = with(density) { 24.dp.toPx() }
            val reach = with(density) { 50.dp.toPx() }
            onAllNodesWithText("Seven", useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }.forEach { name ->
                val mine = numbers.filter { kotlin.math.abs(it.center.y - name.center.y) < reach && it.right <= name.left + 1 }
                assertEquals(1, mine.size, "$tab: numbers beside $name are $mine")
                val gap = name.left - mine.single().right
                assertTrue(gap <= maxGap, "$tab: the number ends ${gap}px before the name")
            }
        }
    }
}
