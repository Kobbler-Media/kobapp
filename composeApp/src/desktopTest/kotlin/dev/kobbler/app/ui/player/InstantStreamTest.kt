package dev.kobbler.app.ui.player

import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.data.Episode
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.Season
import dev.kobbler.app.data.StreamTarget
import dev.kobbler.app.data.TvShow
import io.ktor.client.HttpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A stream the server was asked for while the page sat open, so the press only has to claim it. */
class InstantStreamTest {

    private val show = TvShow(
        id = "show-1", title = "Show", year = 2020, posterUrl = "",
        seasons = listOf(Season(number = 1, episodes = listOf(Episode(id = "ep-1", number = 1, title = "One")))),
    )
    private val target = StreamTarget.episode("ep-1")

    private val warmed =
        """{"state":"ready","prewarm_id":"pw1","option":{"id":"o9","release_title":"Show.S01E01","debrid_provider":"realdebrid",""" +
            """"debrid_provider_id":"p1","info_hash":"h9","cached":true}}"""

    private fun session(id: String) =
        """{"session_id":"$id","method":"direct_play","direct_url":"/api/v1/admin/stream/$id/play","duration_seconds":3600}"""

    private fun routes(prewarm: String = warmed, extra: Map<String, (HttpExchange) -> String> = emptyMap()) =
        mapOf<String, (HttpExchange) -> String>(
            "/api/v1/admin/stream/prewarm" to { _ -> prewarm },
            "/api/v1/admin/stream/prewarm/pw1/touch" to { _ -> "{}" },
            "/api/v1/admin/stream/prewarm/pw1" to { _ -> "" },
            "/api/v1/admin/stream/prewarm/pw1/claim" to { _ -> session("sp") },
            "/api/v1/admin/stream/resolve" to { _ -> session("s1") },
            "/api/v1/admin/stream/sp/outcome" to { _ -> "{}" },
            "/api/v1/admin/stream/s1/outcome" to { _ -> "{}" },
        ) + extra

    private fun withApi(stub: StubServer, block: suspend kotlinx.coroutines.CoroutineScope.(KobblerApi, GlobalPlayerState) -> Unit) =
        runBlocking { withTimeout(10_000) { block(KobblerApi(HttpClient(), stub.url, "token"), GlobalPlayerState()) } }

    @Test
    fun `an open page warms its title after settling, keeps it warm, and lets it go on leaving`() =
        StubServer(routes()).use { stub ->
            withApi(stub) { api, gps ->
                val page = launch {
                    gps.instant.keepWarm(api, target, startTime = { 120 }, busy = { false }, settleMs = 10, touchMs = 30)
                }
                while ("/api/v1/admin/stream/prewarm/pw1/touch" !in stub.requestBodies) delay(10)
                val body = Json.parseToJsonElement(stub.requestBodies.getValue("/api/v1/admin/stream/prewarm")).jsonObject
                assertEquals("episode", body["media_type"]?.jsonPrimitive?.content)
                assertEquals("ep-1", body["media_id"]?.jsonPrimitive?.content)
                assertEquals("120", body["start_time"]?.jsonPrimitive?.content)
                assertTrue("capabilities" in body)

                page.cancel()
                page.join()
                assertTrue("/api/v1/admin/stream/prewarm/pw1" in stub.requestBodies, "the unclaimed prewarm was not released")
            }
        }

    @Test
    fun `nothing is warmed while the player is busy`() = StubServer(routes()).use { stub ->
        withApi(stub) { api, gps ->
            val page = launch { gps.instant.keepWarm(api, target, startTime = { null }, busy = { true }, settleMs = 0, touchMs = 10) }
            delay(150)
            page.cancel()
            assertTrue("/api/v1/admin/stream/prewarm" !in stub.requestBodies)
        }
    }

    @Test
    fun `nothing to warm leaves nothing to claim`() =
        StubServer(routes(), codes = mapOf("/api/v1/admin/stream/prewarm" to 204)).use { stub ->
            withApi(stub) { api, gps ->
                gps.instant.warm(api, target)
                assertNull(gps.instant.claim(api, target))
                assertTrue("/api/v1/admin/stream/prewarm/pw1/claim" !in stub.requestBodies)
            }
        }

    private suspend fun playWarmed(api: KobblerApi, gps: GlobalPlayerState, player: FakePlayer) {
        gps.instant.warm(api, target)
        tryPlayTvWithFallback(
            api = api, player = player, gps = gps, show = show,
            episodeId = "ep-1", seasonIdx = 0, episodeIdx = 0,
            search = { error("searched although a stream was warm") }, option = null,
            onCaching = { _, _ -> error("cached") },
            claim = { gps.instant.claim(api, target) },
        )
    }

    @Test
    fun `play claims the warmed stream without searching or resolving`() = StubServer(routes()).use { stub ->
        withApi(stub) { api, gps ->
            val player = FakePlayer().apply { durationMs.value = 3_600_000 }
            playWarmed(api, gps, player)
            assertTrue(player.loadedUrl?.contains("/sp/") == true, "played ${player.loadedUrl}")
            assertEquals("sp", gps.sessionId)
            assertTrue("/api/v1/admin/stream/resolve" !in stub.requestBodies)
            assertNull(gps.instant.claim(api, target), "a claimed prewarm was handed out twice")
        }
    }

    @Test
    fun `a claim the server refuses falls back to the normal walk`() = listOf(404, 409, 410).forEach { code ->
        StubServer(routes(), codes = mapOf("/api/v1/admin/stream/prewarm/pw1/claim" to code)).use { stub ->
            withApi(stub) { api, gps ->
                val player = FakePlayer().apply { durationMs.value = 3_600_000 }
                gps.instant.warm(api, target)
                tryPlayTvWithFallback(
                    api = api, player = player, gps = gps, show = show,
                    episodeId = "ep-1", seasonIdx = 0, episodeIdx = 0,
                    search = { found(listOf(debridOption("h1"))) }, option = null,
                    onCaching = { _, _ -> error("cached") },
                    claim = { gps.instant.claim(api, target) },
                )
                assertEquals("s1", gps.sessionId, "after a $code")
                assertNull(gps.loadingError)
            }
        }
    }
}
