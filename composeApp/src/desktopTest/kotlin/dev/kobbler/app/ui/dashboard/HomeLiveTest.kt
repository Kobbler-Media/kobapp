package dev.kobbler.app.ui.dashboard

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
import androidx.compose.ui.test.runDesktopComposeUiTest
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
    ) = runDesktopComposeUiTest(width = 1280, height = 900) {
        val hits = AtomicInteger()
        val routes = buildMap<String, (com.sun.net.httpserver.HttpExchange) -> String> {
            put("/api/v1/admin/discover") { _ -> hits.incrementAndGet(); discover }
            if (events != null) put("/api/v1/admin/events") { _ -> events }
        }
        StubServer(routes).use { stub ->
            DataCache.clear()
            val api = KobblerApi(HttpClient(), stub.url, "token")
            setContent { KobappTheme(formFactor = formFactor) { HomeScreen(api = api, formFactor = formFactor) } }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Queued 1").fetchSemanticsNodes().isNotEmpty() }
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
        mainClock.advanceTimeBy(2_000)
        waitForIdle()
        val before = onRoot().captureToImage().toPixelMap()
        onRoot().performMouseInput {
            moveTo(Offset(640f, 450f))
            repeat(5) { scroll(-10f) }
        }
        mainClock.advanceTimeBy(2_000)
        waitForIdle()
        val after = onRoot().captureToImage().toPixelMap()
        return (0 until before.width).sumOf { x -> (0 until 200).count { y -> before[x, y] != after[x, y] } }
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
            waitUntil(timeoutMillis = 10_000) { hits.get() >= 2 }
        }
}
