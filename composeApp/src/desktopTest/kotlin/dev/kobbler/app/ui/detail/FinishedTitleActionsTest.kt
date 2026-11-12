package dev.kobbler.app.ui.detail

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.isNotEnabled
import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.UiTimeoutMs
import dev.kobbler.app.data.Book
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.Movie
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.runUiTest
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.theme.Section
import dev.kobbler.app.ui.detail.tvshow.SHOW_PATH
import dev.kobbler.app.ui.detail.tvshow.assertFocusSettlesOn
import dev.kobbler.app.ui.detail.tvshow.showJson
import dev.kobbler.app.ui.detail.tvshow.showPage
import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Once everything has been watched or read, the row still leads with a way back in rather than a lone overflow. */
@OptIn(ExperimentalTestApi::class)
class FinishedTitleActionsTest {

    private val formFactors = listOf(FormFactor.Desktop to 1280, FormFactor.Phone to 412, FormFactor.TV to 960)
    private val everythingWatched = showJson(watched = { _, _ -> true }, resumes = false)

    private fun page(
        formFactor: FormFactor,
        width: Int,
        routes: Map<String, (HttpExchange) -> String> = emptyMap(),
        section: Section = Section.Home,
        content: @Composable (KobblerApi) -> Unit,
        test: DesktopComposeUiTest.() -> Unit,
    ) = runUiTest(width = width, height = 900) {
        StubServer(routes).use { stub ->
            bodies = stub.requestBodies
            val api = KobblerApi(HttpClient(), stub.url, "token")
            setContent { KobappTheme(formFactor = formFactor, section = section) { content(api) } }
            waitForIdle()
            test()
        }
    }

    private var bodies: Map<String, String> = emptyMap()
    private fun stubBodies() = bodies

    private fun DesktopComposeUiTest.awaitText(text: String) =
        waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun `Rewatch on a fully watched show resets it and plays its first episode in one tap`() {
        for ((formFactor, width) in formFactors) {
            var played: Triple<Int, Int, Boolean>? = null
            val routes = mapOf<String, (HttpExchange) -> String>(
                SHOW_PATH to { everythingWatched },
                "$SHOW_PATH/watch-state" to { """{"updated_count":12}""" },
            )
            page(formFactor, width, routes, content = { api ->
                val show = runBlocking { api.getShow("show-1") }
                TvShowDetailScreen(show = show, formFactor = formFactor, onBack = {}, onPlayEpisode = { s, e, f -> played = Triple(s, e, f) }, api = api)
            }) {
                onNodeWithText("Rewatch").assertIsDisplayed().performClick()
                assertEquals(Triple(0, 0, true), played, "$formFactor")
                assertTrue(onAllNodesWithText("Start over").fetchSemanticsNodes().isEmpty(), "$formFactor asked to confirm")
                waitUntil(timeoutMillis = UiTimeoutMs) { stubBodies().containsKey("$SHOW_PATH/watch-state") }
                assertTrue(stubBodies().getValue("$SHOW_PATH/watch-state").contains("\"watched\":false"), "$formFactor did not reset")
                openOverflowIfAny()
                for (duplicate in listOf("Mark all unwatched", "Rewatch from the start")) assertTrue(!rowHas(duplicate), "$formFactor offers $duplicate")
            }
        }
    }

    @Test
    fun `caught up on a show still airing, the row says when the next episode lands instead of Rewatch`() {
        val caughtUp = showJson(
            inLibrary = { s, e -> s < 4 || e == 1 },
            watched = { s, e -> s < 4 || e == 1 },
            resumes = false,
            airs = { s, e -> "2099-03-04".takeIf { s == 4 && e == 2 } },
        )
        for ((formFactor, width) in formFactors) {
            page(formFactor, width, mapOf(SHOW_PATH to { caughtUp }), content = { api ->
                val show = runBlocking { api.getShow("show-1") }
                TvShowDetailScreen(show = show, formFactor = formFactor, onBack = {}, onPlayEpisode = { _, _, _ -> }, api = api)
            }) {
                onAllNodesWithText("Airs Mar 4, 2099").filterToOne(isNotEnabled()).assertIsDisplayed()
                assertTrue(onAllNodesWithText("Rewatch").fetchSemanticsNodes().isEmpty(), "$formFactor still offers Rewatch")
            }
        }
    }

    @Test
    fun `on a TV, entry focus lands on Rewatch`() = showPage(json = everythingWatched) {
        assertFocusSettlesOn("Rewatch S01 E01")
    }

    @Test
    fun `a watched movie is watched again from the start`() {
        val movie = Movie(id = "m1", title = "Movie", year = 2020, posterUrl = "", filePath = "/m.mkv", runtimeMinutes = 100, isWatched = true)
        for ((formFactor, width) in formFactors) {
            var fromStart: Boolean? = null
            page(formFactor, width, content = { api ->
                MovieDetailScreen(movie = movie, api = api, formFactor = formFactor, onBack = {}, onPlay = { fromStart = it }, onWatchedChanged = {})
            }) {
                onNodeWithText("Watch again").assertIsDisplayed().performClick()
                assertEquals(true, fromStart, "$formFactor")
            }
        }
    }

    @Test
    fun `a finished book is read again, and a finished audiobook listened to again`() {
        val book = Book(id = "b1", title = "Thud!", author = "Pratchett", coverUrl = "", hasAudiobook = true, formats = listOf("epub"))
        val finished = mapOf<String, (HttpExchange) -> String>(
            "/api/v1/admin/books/b1/reader-progress" to { """{"progress_percent":100.0}""" },
            "/api/v1/admin/books/b1/audio-position" to { """{"file_id":"f1","position_seconds":36000,"progress_percent":99.5}""" },
        )
        for ((section, lead) in listOf(Section.Books to "Read again", Section.Audio to "Listen again")) {
            for ((formFactor, width) in formFactors) {
                var opened: Pair<String, Boolean>? = null
                page(formFactor, width, finished, section, content = { api ->
                    BookDetailScreen(
                        book = book, api = api, formFactor = formFactor, onBack = {},
                        onRead = { opened = "read" to it }, onListen = { opened = "listen" to it },
                    )
                }) {
                    awaitText(lead)
                    onNodeWithText(lead).assertIsDisplayed().performClick()
                    assertEquals((if (section == Section.Books) "read" else "listen") to true, opened, "$section $formFactor")
                }
            }
        }
    }
}
