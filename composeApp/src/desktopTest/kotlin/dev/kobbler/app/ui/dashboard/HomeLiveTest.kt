package dev.kobbler.app.ui.dashboard

import dev.kobbler.app.runUiTest
import dev.kobbler.app.UiTimeoutMs
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.toPixelMap
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.DataCache
import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HomeLiveTest {

    private val discover =
        """{"watch_next":[{"media_type":"movie","media_id":"m1","title":"Queued 1","link":"/movies/m1","progress_percent":40}],""" +
            """"recently_added":[{"item_type":"movie","id":"r1","title":"Recent 1","subtitle":"2024","link":"/movies/r1"}],"sections":[]}"""

    private fun home(
        formFactor: FormFactor,
        events: String? = null,
        test: DesktopComposeUiTest.(discoverHits: AtomicInteger) -> Unit,
    ) = runUiTest(width = 1280, height = 900) {
        val hits = AtomicInteger()
        val routes = buildMap<String, (com.sun.net.httpserver.HttpExchange) -> String> {
            put("/api/v1/admin/discover") { _ -> hits.incrementAndGet(); discover }
            if (events != null) put("/api/v1/admin/events") { _ -> events }
        }
        StubServer(routes).use { stub ->
            DataCache.clear()
            val api = KobblerApi(HttpClient(), stub.url, "token")
            setContent { KobappTheme(formFactor = formFactor) { HomeScreen(api = api, formFactor = formFactor) } }
            waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithText("Queued 1").fetchSemanticsNodes().isNotEmpty() }
            test(hits)
        }
    }

    @Test
    fun `desktop home opens on up next exactly like tv`() {
        val upNextTop = listOf(FormFactor.TV, FormFactor.Desktop).associateWith { formFactor ->
            var top = 0f
            home(formFactor) {
                for (action in listOf("Details", "Resume")) {
                    assertTrue(onAllNodesWithText(action).fetchSemanticsNodes().isEmpty(), "$formFactor: hero still has '$action'")
                }
                top = onNodeWithText("Up Next").getUnclippedBoundsInRoot().top.value
            }
            top
        }
        assertEquals(upNextTop.getValue(FormFactor.TV), upNextTop.getValue(FormFactor.Desktop), 1f, "Up Next sits lower on desktop: $upNextTop")
    }

    private fun DesktopComposeUiTest.pixelsChangedByWheelingUp(): Int {
        val before = settledTop()
        onRoot().performMouseInput {
            moveTo(Offset(640f, 450f))
            repeat(5) { scroll(-10f) }
        }
        val after = settledTop()
        return before.indices.count { before[it] != after[it] }
    }

    // Artwork arrives on the network's time, not the test clock's, so on a busy machine a
    // poster can land between the two captures and read as something the wheel drew.
    private fun DesktopComposeUiTest.settledTop(): List<Int> {
        var last: List<Int>? = null
        repeat(40) {
            mainClock.advanceTimeBy(500)
            waitForIdle()
            val map = onRoot().captureToImage().toPixelMap()
            val top = (0 until map.width).flatMap { x -> (0 until 200).map { y -> map[x, y].hashCode() } }
            if (top == last) return top
            last = top
            Thread.sleep(100)
        }
        return last!!
    }

    @Test
    fun `wheeling up at the top of a wide home pulls nothing down`() {
        home(FormFactor.Phone) { assertTrue(pixelsChangedByWheelingUp() > 0, "the probe cannot see a refresh indicator") }
        for (formFactor in listOf(FormFactor.Desktop, FormFactor.TV)) home(formFactor) {
            assertEquals(0, pixelsChangedByWheelingUp(), "$formFactor: wheeling up drew something at the top of the page")
        }
    }

    @Test
    fun `home refetches when another device saves progress`() =
        home(FormFactor.Desktop, events = "event: watch_progress\ndata: {\"kind\":\"watch_progress\",\"media_id\":\"m1\"}\n\n") { hits ->
            waitUntil(timeoutMillis = UiTimeoutMs) { hits.get() >= 2 }
        }
}
