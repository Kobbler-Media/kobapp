package dev.kobbler.app.ui.player

import dev.kobbler.app.data.KobblerApi
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * What a play costs before VLC is handed a URL.
 *
 * The session and the resume position are two independent questions for the server, and
 * asking the second only after the first has answered is a round trip spent on nothing.
 * Timed rather than counted, because the fault is the ordering and not the calls.
 */
class PlaybackStartTest {

    private val delayMs = 300L

    private fun slowRoutes() = mapOf(
        "/api/v1/admin/playback/sessions" to { _: com.sun.net.httpserver.HttpExchange ->
            Thread.sleep(delayMs)
            """{"session_id":"s1","direct_url":"http://stub/stream.mkv","method":"direct"}"""
        },
        "/api/v1/admin/movies/mov-1/watch-state" to { _: com.sun.net.httpserver.HttpExchange ->
            Thread.sleep(delayMs)
            """{"position_seconds":600}"""
        },
    )

    @Test
    fun `the session and the resume position are asked for together`() {
        StubServer(slowRoutes()).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val start = TimeSource.Monotonic.markNow()
            val result = runBlocking { startPlayback(api, "movie", "mov-1") }
            val elapsed = start.elapsedNow().inWholeMilliseconds

            assertNotNull(result, "no session was minted")
            assertEquals(600, result.seekToSec, "the resume position was dropped")
            assertTrue(
                elapsed < delayMs * 2,
                "took ${elapsed}ms for two ${delayMs}ms calls — they ran one after the other",
            )
        }
    }

    /**
     * Handing the old session back is housekeeping, and the server does it the slow way —
     * killing transcodes and reaping their processes. Nothing about opening the next
     * stream depends on it having finished, so nothing should wait for it.
     */
    @Test
    fun `closing the previous session does not hold up the next one`() {
        val routes = mapOf(
            "/api/v1/admin/playback/sessions/old-1" to { _: com.sun.net.httpserver.HttpExchange ->
                Thread.sleep(delayMs * 3)
                "{}"
            },
        )
        StubServer(routes).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val player = FakePlayer().apply {
                state.value = dev.kobbler.kobplayer.PlayerState.PAUSED
                durationMs.value = 60_000
            }
            val gps = GlobalPlayerState().apply { sessionId = "old-1"; mediaId = "mov-0" }

            val start = TimeSource.Monotonic.markNow()
            runBlocking {
                gps.load(
                    player = player, api = api, type = "movie", id = "mov-1",
                    streamUrl = "http://127.0.0.1:1/stream", newSessionId = "s1",
                    newTitle = "T", newSubtitle = null,
                    newPosterUrl = null, newLogoUrl = null, destination = null,
                )
            }
            val elapsed = start.elapsedNow().inWholeMilliseconds

            assertTrue(
                elapsed < delayMs * 2,
                "load() spent ${elapsed}ms waiting on the old session's teardown",
            )
        }
    }

    /** A server with nothing to play is not a server to go on and ask a second question of. */
    @Test
    fun `no session means no start`() {
        StubServer(emptyMap()).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            assertEquals(null, runBlocking { startPlayback(api, "movie", "mov-1") })
        }
    }
}
