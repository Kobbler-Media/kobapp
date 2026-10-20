package dev.kobbler.app.ui.player

import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.data.ApiException
import dev.kobbler.app.data.Episode
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.Season
import dev.kobbler.app.data.StreamOption
import dev.kobbler.app.data.StreamQuality
import dev.kobbler.app.data.TvShow
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A debrid stream that resolves is not yet a stream that plays: the session can still fail
 * to probe, or hand the player something it cannot open. Those used to stop the walk dead
 * on a stack trace with the rest of the option list untried.
 */
class DebridFallbackTest {

    private val show = TvShow(
        id = "show-1", title = "Show", year = 2020, posterUrl = "",
        seasons = listOf(Season(number = 1, episodes = listOf(Episode(id = "ep-1", number = 1, title = "One")))),
    )

    private fun option(n: Int) = StreamOption(
        id = "o$n", releaseTitle = "Show.S01E01.v$n", size = 1, indexer = "idx", infoHash = "h$n",
        quality = StreamQuality("1080p", null, null, null), qualityJson = null, totalScore = 1.0,
        debridProvider = "realdebrid", debridProviderId = "p1", cached = true,
        sourceType = "torrent", fileId = 1, publishDate = null,
    )

    private val options = (1..3).map(::option)

    private val resolved = CopyOnWriteArrayList<String>()

    private var stub: StubServer? = null

    // The stub has read the request body by the time a handler runs, so it is taken from there.
    private fun resolveBy(answer: (hash: String) -> String) = { _: HttpExchange ->
        val hash = Json.parseToJsonElement(stub!!.requestBodies.getValue("/api/v1/admin/stream/resolve"))
            .jsonObject["info_hash"]!!.jsonPrimitive.content
        resolved += hash
        answer(hash)
    }

    private fun session(n: String) =
        """{"session_id":"s$n","method":"DirectPlay","direct_url":"/api/v1/admin/stream/s$n/play","duration_seconds":3600}"""

    private fun routes(resolve: (HttpExchange) -> String): Map<String, (HttpExchange) -> String> = mapOf(
        "/api/v1/admin/stream/resolve" to resolve,
        "/api/v1/admin/stream/s1/outcome" to { _ -> "{}" },
        "/api/v1/admin/stream/s2/outcome" to { _ -> "{}" },
        "/api/v1/admin/stream/s1b/outcome" to { _ -> "{}" },
        "/api/v1/admin/tv/episodes/ep-1/want" to { _ -> "{}" },
    )

    private fun watched(stub: StubServer, player: FakePlayer, block: suspend CoroutineScope.(KobblerApi, GlobalPlayerState) -> Unit) =
        runBlocking {
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val gps = GlobalPlayerState()
            val watcher = launch { gps.watchPlayback(player, api) }
            try {
                withTimeout(20_000) { block(api, gps) }
            } finally {
                watcher.cancel()
            }
        }

    private fun StubServer.outcome(sid: String) =
        requestBodies["/api/v1/admin/stream/$sid/outcome"]?.let { Json.parseToJsonElement(it).jsonObject }

    @Test
    fun `a stream that resolves but will not open moves on to the next option`() {
        val player = FakePlayer().apply {
            durationMs.value = 3_600_000
            failsToOpen = { it.contains("/s1/") }
        }
        StubServer(routes(resolveBy { hash -> session(hash.removePrefix("h")) })).also { stub = it }.use { stub ->
            watched(stub, player) { api, gps ->
                tryPlayTvWithFallback(
                    api = api, player = player, gps = gps, show = show,
                    episodeId = "ep-1", seasonIdx = 0, episodeIdx = 0,
                    options = options, option = options.first(),
                    onCaching = { _, _ -> error("nothing here needs caching") },
                )
                while (gps.sessionId != "s2" || stub.outcome("s2") == null) delay(20)

                assertEquals(listOf("h1", "h2"), resolved.toList())
                assertTrue(player.loadedUrl?.contains("/s2/") == true, "still on ${player.loadedUrl}")
                assertNull(gps.loadingError, "raised the dialog with options left to try")
                assertEquals(false, stub.outcome("s1")?.get("ok")?.jsonPrimitive?.boolean, "the dead release went unreported")
                assertEquals(true, stub.outcome("s2")?.get("ok")?.jsonPrimitive?.boolean, "the working release went unreported")
            }
        }
    }

    @Test
    fun `when nothing plays the dialog says why and offers a download`() {
        val refused = """{"error":"Real-Debrid refused this release. Try a different source.","reason":"blocked"}"""
        val codes = mapOf("/api/v1/admin/stream/resolve" to 502)
        StubServer(routes { _ -> refused }, codes).use { stub ->
            watched(stub, FakePlayer()) { api, gps ->
                tryPlayTvWithFallback(
                    api = api, player = FakePlayer(), gps = gps, show = show,
                    episodeId = "ep-1", seasonIdx = 0, episodeIdx = 0,
                    options = options, option = options.first(),
                    onCaching = { _, _ -> error("nothing here needs caching") },
                )

                val help = assertNotNull(gps.failureHelp, "no friendly failure")
                assertNotNull(gps.loadingError, "the diagnostic went missing")
                assertEquals("None of the 3 streams we tried would play. Your debrid service refused the release.", help.summary)
                assertTrue(gps.loadingError!!.contains("Real-Debrid refused this release"), "the server's reason was dropped")

                assertNotNull(help.want, "an episode with no file should be wantable").run()
                assertTrue("/api/v1/admin/tv/episodes/ep-1/want" in stub.requestBodies, "Want did not reach the server")
            }
        }
    }

    @Test
    fun `the server's error survives as the exception message`() {
        val body = """{"error":"Your debrid service is rate-limiting requests.","reason":"rate_limited","detail":"Rate limited"}"""
        StubServer(mapOf("/api/v1/admin/stream/resolve" to { _ -> body }), mapOf("/api/v1/admin/stream/resolve" to 502)).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val e = runCatching { runBlocking { api.streamResolve(options.first(), "episode", "ep-1") } }.exceptionOrNull()
            assertIs<ApiException>(e)
            assertEquals("Your debrid service is rate-limiting requests.", e.message)
            assertEquals("rate_limited", e.reason)
        }
    }

    @Test
    fun `a stream that drops after playing is reopened once on the same release, then given up on`() {
        val player = FakePlayer().apply { durationMs.value = 3_600_000 }
        val minted = mutableMapOf<String, Int>()
        val resolve = resolveBy { hash ->
            val n = minted.merge(hash, 1, Int::plus)!!
            session(hash.removePrefix("h") + if (n > 1) "b" else "")
        }
        StubServer(routes(resolve)).also { stub = it }.use { stub ->
            watched(stub, player) { api, gps ->
                tryPlayTvWithFallback(
                    api = api, player = player, gps = gps, show = show,
                    episodeId = "ep-1", seasonIdx = 0, episodeIdx = 0,
                    options = options, option = options.first(),
                    onCaching = { _, _ -> error("nothing here needs caching") },
                )
                while (stub.outcome("s1") == null) delay(20)
                player.currentMs.value = 600_000

                player.error.value = RuntimeException("connection reset")
                while (gps.sessionId != "s1b" || stub.outcome("s1b") == null) delay(20)
                assertEquals(listOf("h1", "h1"), resolved.toList(), "did not reopen the release that was playing")
                assertEquals(600, player.loadedSource?.startTimeSec, "reopened somewhere other than where it dropped")
                assertEquals(true, stub.outcome("s1")?.get("ok")?.jsonPrimitive?.boolean, "a hiccup was blamed on the release")

                player.error.value = RuntimeException("connection reset again")
                while (gps.failureHelp == null) delay(20)
                assertEquals("The stream stopped and couldn't be picked up again.", gps.failureHelp!!.summary)
                assertEquals(false, stub.outcome("s1b")?.get("ok")?.jsonPrimitive?.boolean)
            }
        }
    }

    @Test
    fun `a search with nothing in it says so and offers a download instead of an empty list`() {
        StubServer(routes { _ -> error("nothing to resolve") }).use { stub ->
            watched(stub, FakePlayer()) { api, gps ->
                tryPlayTvWithFallback(
                    api = api, player = FakePlayer(), gps = gps, show = show,
                    episodeId = "ep-1", seasonIdx = 0, episodeIdx = 0,
                    options = emptyList(), option = null,
                    searchErrors = listOf("No debrid providers configured"),
                    onCaching = { _, _ -> error("nothing here needs caching") },
                )
                val help = assertNotNull(gps.failureHelp)
                assertEquals("No streams were found for this.", help.summary)
                assertTrue(help.alternatives.isEmpty())
                assertNotNull(help.want)
                assertTrue(gps.loadingError!!.contains("No debrid providers configured"))
            }
        }
    }
}
