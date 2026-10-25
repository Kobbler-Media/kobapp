package dev.kobbler.app.ui.player

import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.data.Episode
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.Season
import dev.kobbler.app.data.StreamOption
import dev.kobbler.app.data.StreamQuality
import dev.kobbler.app.data.TvShow
import io.ktor.client.HttpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * The incident: the only release for an episode resolved, the player could not open it, and
 * the dialog came up offering a download — then, forty-five seconds later, a second dialog
 * replaced it saying nothing had played in 45s. The start supervision for the dead session
 * was still running after the walk had already given up on it.
 */
class DebridGiveUpTest {

    private val show = TvShow(
        id = "show-1", title = "Show", year = 2020, posterUrl = "",
        seasons = listOf(Season(number = 4, episodes = listOf(Episode(id = "ep-17", number = 17, title = "Hackidu")))),
    )

    private val only = StreamOption(
        id = "o1", releaseTitle = "Show.S04E17.1080p.hevc", size = 1, indexer = "idx", infoHash = "h1",
        quality = StreamQuality("1080p", "hevc", null, null), qualityJson = null, totalScore = 1.0,
        debridProvider = "realdebrid", debridProviderId = "p1", cached = true,
        sourceType = "torrent", fileId = 16, publishDate = null,
    )

    private val preparing =
        """{"session_id":"s1","state":"preparing","method":"direct_play","is_live":false,"source_readable":false}"""

    private val routes: Map<String, (HttpExchange) -> String> = mapOf(
        "/api/v1/admin/stream/resolve" to { _ ->
            """{"session_id":"s1","method":"direct_play","direct_url":"/api/v1/admin/stream/s1/play","duration_seconds":0}"""
        },
        "/api/v1/admin/playback/sessions/s1/status" to { _ -> preparing },
        "/api/v1/admin/stream/s1/outcome" to { _ -> "{}" },
        "/api/v1/admin/stream/s1" to { _ -> "{}" },
    )

    private fun givenUp(block: suspend (GlobalPlayerState) -> Unit) = runBlocking {
        val player = FakePlayer().apply { failsToOpen = { it.contains("/s1/") } }
        StubServer(routes).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val gps = GlobalPlayerState()
            val watcher = launch { gps.watchPlayback(player, api) }
            try {
                withTimeout(20_000) {
                    tryPlayTvWithFallback(
                        api = api, player = player, gps = gps, show = show,
                        episodeId = "ep-17", seasonIdx = 0, episodeIdx = 0,
                        search = { found(listOf(only)) }, option = only,
                        onCaching = { _, _ -> error("nothing here needs caching") },
                    )
                    while (gps.failureHelp == null) delay(20)
                    block(gps)
                }
            } finally {
                watcher.cancel()
            }
        }
    }

    @Test
    fun `a walk that has given up is not given up on a second time`() = givenUp { gps ->
        val first = gps.loadingError
        val help = gps.failureHelp

        // The supervision left behind has no session to ask about, so its own budget is the
        // first thing that could fire; the ceiling is too long to wait out in real time.
        delay(DIRECT_START_BUDGET_MS + 1_500)

        assertSame(help, gps.failureHelp, "the dialog's download and release options were replaced")
        assertEquals(first, gps.loadingError, "a second failure replaced the first")
        assertFalse(gps.loadingError.orEmpty().contains("Nothing played"), "the stale start timed out over the real failure")
    }

    @Test
    fun `the one failure says what it was`() = givenUp { gps ->
        val help = assertNotNull(gps.failureHelp)
        assertEquals("That stream wouldn't play.", help.summary)
        assertNotNull(help.want)
    }
}
