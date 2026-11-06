package dev.kobbler.app.ui.detail

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.UiTimeoutMs
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.ui.detail.tvshow.SHOW_PATH
import dev.kobbler.app.ui.detail.tvshow.detailPage
import dev.kobbler.app.ui.detail.tvshow.showJson
import dev.kobbler.app.ui.navigation.Destination
import dev.kobbler.app.ui.navigation.NavigationState
import dev.kobbler.app.ui.player.GlobalPlayerState
import dev.kobbler.app.ui.player.reportProgress
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test

/** Progress saved while a detail page was away, or behind it, shows on the page without reopening it. */
@OptIn(ExperimentalTestApi::class)
class ProgressRefreshTest {

    private fun DesktopComposeUiTest.awaitOffered(label: String) =
        waitUntil(timeoutMillis = UiTimeoutMs) { rowHas(label) }

    private fun DesktopComposeUiTest.awaitText(text: String, substring: Boolean = false) =
        waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithText(text, substring = substring, ignoreCase = true).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun `back from the reader or the audiobook, a book page shows the new progress whenever its save lands`() {
        for (listening in listOf(false, true)) for (savedAfterReturn in listOf(false, true)) {
            val saved = AtomicBoolean(false)
            val routes = mapOf<String, (HttpExchange) -> String>(
                "/api/v1/library/books/b1" to { """{"id":"b1","title":"Novel","formats":["EPUB"],"has_audiobook":true,"duration_seconds":36000}""" },
                "/api/v1/admin/books/b1/reader-progress" to { ex ->
                    if (ex.requestMethod != "GET") { saved.set(true); "{}" }
                    else if (saved.get() && !listening) """{"progress_percent":40.0}""" else "{}"
                },
                "/api/v1/admin/books/b1/audio-position" to { ex ->
                    if (ex.requestMethod != "GET") { saved.set(true); "{}" }
                    else if (saved.get() && listening) """{"file_id":"f1","position_seconds":14400,"progress_percent":40.0}""" else "{}"
                },
            )
            val nav = NavigationState()
            detailPage(Destination.BookDetail("b1"), routes, nav = nav, formFactor = FormFactor.Desktop, width = 1280, height = 900) { stub ->
                val api = KobblerApi(HttpClient(), stub.url, "token")
                awaitOffered("Read")
                val save = {
                    runBlocking {
                        if (listening) api.saveAudioPosition("b1", "f1", 14_400.0, 40.0) else api.saveReaderProgress("b1", null, 40.0)
                    }
                }
                runOnIdle { nav.navigate(if (listening) Destination.AudiobookPlayer("b1") else Destination.BookReader("b1")) }
                waitForIdle()
                if (!savedAfterReturn) save()
                runOnIdle { nav.goBack() }
                if (savedAfterReturn) {
                    awaitOffered("Read")
                    save()
                }
                awaitOffered(if (listening) "Resume listening" else "Resume reading")
            }
        }
    }

    @Test
    fun `an episode watched after Rewatch from the start shows as watched, and what came before stays reset`() {
        val reset = AtomicBoolean(false)
        val routes = mapOf<String, (HttpExchange) -> String>(
            SHOW_PATH to { if (reset.get()) showJson(watched = { _, _ -> false }, resumes = false) else showJson() },
            "$SHOW_PATH/watch-state" to { reset.set(true); """{"updated_count":12}""" },
        )
        val gps = GlobalPlayerState()
        detailPage(Destination.TvShowDetail("show-1"), routes, gps = gps, formFactor = FormFactor.Desktop, width = 1280, height = 900) { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            awaitText("6 / 12 watched")
            runBlocking { gps.reportProgress(api, "episode", "e3-2", 2_700_000L, finished = true) }
            awaitText("7 / 12 watched")

            performAction("Rewatch from the start")
            onNodeWithText("Start over").performClick()
            awaitText("0 / 12 watched")

            runBlocking { gps.reportProgress(api, "episode", "e1-1", 2_700_000L, finished = true) }
            awaitText("1 / 12 watched")
            awaitText("S01 E02", substring = true)
        }
    }

    @Test
    fun `a movie page picks up progress another device or the cast receiver saved`() {
        val progressed = AtomicBoolean(false)
        val elsewhere = CountDownLatch(1)
        val routes = mapOf<String, (HttpExchange) -> String>(
            "/api/v1/admin/movies/m1" to {
                val position = if (progressed.get()) ""","watch_position_seconds":1800""" else ""
                """{"id":"m1","title":"Film","year":2020,"runtime":100,"is_in_library":true,"watched":false$position}"""
            },
            "/api/v1/admin/events" to {
                elsewhere.await(UiTimeoutMs, TimeUnit.MILLISECONDS)
                "event: watch_progress\ndata: " +
                    """{"kind":"watch_progress","media_type":"movie","media_id":"m1","scope_id":"m1","position_seconds":1800,"watched":false,"client_id":"cast-receiver"}""" +
                    "\n\n"
            },
        )
        detailPage(Destination.MovieDetail("m1"), routes, formFactor = FormFactor.Desktop, width = 1280, height = 900) {
            awaitOffered("Play")
            progressed.set(true)
            elsewhere.countDown()
            awaitOffered("Resume")
        }
    }
}
