package dev.kobbler.app.ui.player

import androidx.compose.ui.test.ExperimentalTestApi
import dev.kobbler.app.data.Episode
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.Season
import dev.kobbler.app.data.TvShow
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.runUiTest
import io.ktor.client.HttpClient
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where to resume a show is the server's call: it walks the viewer's history and counts
 * streamable episodes as well as files. The page only guesses for a show nobody has started,
 * and then the guess is the first episode, warmed up so Play is instant. It used to guess the
 * first episode it held a file for — on a show streamed to season 7 and collected from season
 * 19, a transcode of S19E01 opened just for looking at the page.
 */
@OptIn(ExperimentalTestApi::class)
class StartEpisodeGuessTest {

    private fun show(watchedS07: Boolean) = TvShow(
        id = "show-1", title = "Show", year = 2005, posterUrl = "",
        seasons = listOf(
            Season(number = 0, episodes = listOf(Episode(id = "s00e01", number = 1, title = "Special"))),
            Season(number = 1, episodes = listOf(Episode(id = "s01e01", number = 1, title = "Pilot"))),
            Season(number = 7, episodes = listOf(Episode(id = "s07e15", number = 15, title = "Streamed", isWatched = watchedS07))),
            Season(number = 19, episodes = listOf(Episode(id = "s19e01", number = 1, title = "Collected", filePath = "/s19e01.mkv"))),
        ),
        continueEpisode = null,
    )

    private fun requestsOpening(show: TvShow, autoPlay: Boolean): List<String> {
        val requests = CopyOnWriteArrayList<String>()
        val record = { exchange: com.sun.net.httpserver.HttpExchange ->
            requests += exchange.requestURI.path
            """{"watched":false,"position_seconds":0}"""
        }
        val routes = listOf("s00e01", "s01e01", "s07e15", "s19e01").flatMap { id ->
            listOf("/api/v1/admin/tv/episodes/$id/watch-state" to record, "/api/v1/admin/tv/episodes/$id/files" to record)
        }.toMap() + ("/api/v1/admin/playback/sessions" to record)
        runUiTest {
            StubServer(routes).use { stub ->
                val api = KobblerApi(HttpClient(), stub.url, "token").apply { canStream = true }
                val gps = GlobalPlayerState()
                setContent {
                    InlineTvPlayer(
                        show = show, api = api, formFactor = FormFactor.TV,
                        player = FakePlayer(), gps = gps, autoPlay = autoPlay, onBack = {},
                    )
                }
                waitForIdle()
                Thread.sleep(1_500)
                waitForIdle()
                if (requests.isEmpty()) assertNull(gps.mediaId)
            }
        }
        return requests.toList()
    }

    @Test
    fun `a show already under way with nothing to continue starts nothing`() {
        listOf(false, true).forEach { autoPlay ->
            assertEquals(emptyList(), requestsOpening(show(watchedS07 = true), autoPlay), "autoPlay=$autoPlay")
        }
    }

    @Test
    fun `an unstarted show warms its first episode, not the first one on disk`() {
        val requests = requestsOpening(show(watchedS07 = false), autoPlay = false)
        assertTrue(requests.any { "s01e01" in it }, "requests: $requests")
        assertTrue(requests.none { "s19e01" in it || "s00e01" in it }, "requests: $requests")
    }
}
