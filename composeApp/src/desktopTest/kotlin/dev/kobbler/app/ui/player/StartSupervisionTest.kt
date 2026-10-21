package dev.kobbler.app.ui.player

import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.data.KobblerApi
import io.ktor.client.HttpClient
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import dev.kobbler.kobplayer.PlayerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What a start is allowed to cost, and how quickly a lost one is called.
 *
 * A locally stored file that is direct-played has nothing to prepare — no transcode to
 * spin up, no first segment to wait for — so a spinner over one is already a fault by the
 * time it is worth looking at. And everything that can go wrong on the server is known
 * there the moment it happens, so waiting out a budget to discover it is the slowest
 * possible way to find out.
 */
class StartSupervisionTest {

    private fun statusRoute(body: String) = mapOf(
        "/api/v1/admin/playback/sessions/s1/status" to { _: HttpExchange -> body },
    )

    private fun watching(method: String? = null) = GlobalPlayerState().apply {
        mediaType = "movie"
        mediaId = "mov-1"
        sessionId = "s1"
        sessionMethod = method
        title = "Project Hail Mary"
        isStarting = true
    }

    private fun stalled() = FakePlayer().apply {
        playStalls = true
        durationMs.value = 9_444_000
    }

    @Test
    fun `a local file gets a far shorter budget than a transcode`() {
        val gps = GlobalPlayerState()
        assertTrue(
            gps.startBudgetMs("direct_play") < gps.startBudgetMs("video_transcode"),
            "a direct-played file is being given a transcode's patience",
        )
        assertEquals(
            LIVE_START_BUDGET_MS,
            GlobalPlayerState().apply { isLive = true }.startBudgetMs("direct_play"),
            "a tuner was held to a local file's budget",
        )
    }

    /** The server already knows. Asking is one request; timing out is the whole budget. */
    @Test
    fun `a session the server has failed is called immediately`() = runTest {
        val body = """{"session_id":"s1","state":"failed","method":"video_transcode",
            "is_live":false,"error":"transcode_exited","source_readable":true,
            "message":"The transcode process exited before producing anything"}"""
        StubServer(statusRoute(body)).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")

            val outcome = watching().superviseStart(stalled(), api, probeIntervalMs = 50)

            assertIs<StartOutcome.Broken>(outcome)
            assertTrue(
                outcome.cause.message?.contains("exited before producing") == true,
                "the server said why and it was thrown away: ${outcome.cause.message}",
            )
            assertTrue(
                testScheduler.currentTime < DIRECT_START_BUDGET_MS,
                "waited ${testScheduler.currentTime}ms for something the server had already answered",
            )
        }
    }

    @Test
    fun `a session the server has forgotten is called immediately`() = runTest {
        StubServer(emptyMap()).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val outcome = watching().superviseStart(stalled(), api, probeIntervalMs = 50)
            assertIs<StartOutcome.Broken>(outcome)
            assertTrue(testScheduler.currentTime < DIRECT_START_BUDGET_MS)
        }
    }

    /**
     * The answer for a session the app has already moved on from says nothing about the
     * one it is starting now. Read as a death, it tore down a healthy start and reconnected.
     */
    @Test
    fun `a session replaced while it was being asked about is not called lost`() = runTest {
        val gps = watching()
        val healthy = """{"session_id":"s2","state":"ready","method":"direct_play",
            "is_live":false,"source_readable":true}"""
        val routes = mapOf(
            "/api/v1/admin/playback/sessions/s1/status" to { _: HttpExchange ->
                gps.sessionId = "s2"
                "{}"
            },
            "/api/v1/admin/playback/sessions/s2/status" to { _: HttpExchange -> healthy },
        )
        StubServer(routes, codes = mapOf("/api/v1/admin/playback/sessions/s1/status" to 404)).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")

            val outcome = gps.superviseStart(stalled(), api, probeIntervalMs = 50)

            assertIs<StartOutcome.Stalled>(outcome)
        }
    }

    /**
     * A stream the server says it is still building is a wait, and the server enforces
     * its own end to it. Only the ceiling stops this one.
     */
    @Test
    fun `a preparing session is waited out to the ceiling`() = runTest {
        val body = """{"session_id":"s1","state":"preparing","method":"video_transcode",
            "is_live":false,"source_readable":true,"transcode_running":true,"segments_ready":0}"""
        StubServer(statusRoute(body)).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")

            val outcome = watching().superviseStart(stalled(), api, probeIntervalMs = 50)

            assertIs<StartOutcome.Stalled>(outcome)
            assertTrue(
                testScheduler.currentTime >= START_CEILING_MS,
                "gave up after ${testScheduler.currentTime}ms on a transcode the server said was coming",
            )
        }
    }

    /**
     * The other half of that: a session the server calls healthy which still has not
     * produced a picture is the client's call, and it makes it on its own budget.
     */
    @Test
    fun `a ready session that never plays is called on its own budget`() = runTest {
        val body = """{"session_id":"s1","state":"ready","method":"video_transcode",
            "is_live":false,"source_readable":true,"transcode_running":true,"segments_ready":8}"""
        StubServer(statusRoute(body)).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")

            val outcome = watching().superviseStart(stalled(), api, probeIntervalMs = 50)

            assertIs<StartOutcome.Stalled>(outcome)
            assertTrue(
                testScheduler.currentTime < START_CEILING_MS,
                "waited the whole ceiling out on a session the server called ready",
            )
        }
    }

    /**
     * The budget the session was created with, not the one a probe has yet to confirm —
     * a local file must not be given a transcode's patience while the first probe flies.
     */
    @Test
    fun `a direct-played file gives up on its own budget`() = runTest {
        val body = """{"session_id":"s1","state":"ready","method":"direct_play",
            "is_live":false,"source_readable":true}"""
        StubServer(statusRoute(body)).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")

            val outcome = watching(method = "direct_play")
                .superviseStart(stalled(), api, probeIntervalMs = 50)

            assertIs<StartOutcome.Stalled>(outcome)
            assertTrue(
                testScheduler.currentTime < TRANSCODE_START_BUDGET_MS,
                "a local file sat in a spinner for ${testScheduler.currentTime}ms",
            )
        }
    }

    /** An unreachable server is not a dead session. */
    @Test
    fun `a probe that cannot be delivered is not a verdict`() = runTest {
        val api = KobblerApi(HttpClient(), "http://127.0.0.1:1", "token")

        val outcome = watching(method = "direct_play")
            .superviseStart(stalled(), api, probeIntervalMs = 50)

        assertIs<StartOutcome.Stalled>(outcome, "read a failed request as a failed session")
    }

    @Test
    fun `a stream that plays is left alone`() = runTest {
        StubServer(emptyMap()).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val player = FakePlayer().apply { durationMs.value = 9_444_000 }
            player.play()

            assertEquals(
                StartOutcome.Playing,
                watching().superviseStart(player, api, probeIntervalMs = 50),
            )
        }
    }

    private fun status(state: String, feed: String? = null) =
        """{"session_id":"s1","state":"$state","method":"direct_play","is_live":false,"source_readable":true""" +
            (feed?.let { ""","feed":$it""" } ?: "") + "}"

    private fun feed(served: Long, idleMs: Long?, downloadBps: Long = 0, downloaded: Long = 0) =
        """{"requests_open":1,"bytes_served":$served,"idle_ms":${idleMs ?: "null"},"source":"remote",""" +
            """"downloaded_bytes":$downloaded,"total_bytes":909281669,"download_bps":$downloadBps}"""

    /**
     * Driven by the probes rather than by scheduled coroutines: virtual time leaps ahead
     * while a probe is out on a real socket, so anything scheduled would fire mid-request.
     */
    private fun TestScope.timeline(
        vararg phases: Pair<Long, (Long) -> String>,
        player: FakePlayer? = null,
        playsAtMs: Long = Long.MAX_VALUE,
    ) = mapOf(
        "/api/v1/admin/playback/sessions/s1/status" to { _: HttpExchange ->
            val now = testScheduler.currentTime
            if (player != null && now >= playsAtMs) {
                player.isPlaying.value = true
                player.state.value = PlayerState.PLAYING
            }
            phases.last { now >= it.first }.second(now)
        },
    )

    /**
     * The incident: the server spent 5.5s getting the first bytes while it called the
     * session preparing, and the six-second budget had been running the whole time. The
     * file was healthy and the bytes were flowing when the start was called lost.
     */
    @Test
    fun `a start whose bytes are flowing is not called lost because the server was slow to begin`() = runTest {
        val player = stalled()
        val routes = timeline(
            0L to { _ -> status("preparing") },
            5_500L to { now -> status("ready", feed(served = (now - 5_500) * 2_000, idleMs = 200)) },
            player = player, playsAtMs = 14_000,
        )
        StubServer(routes).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")

            val outcome = watching(method = "direct_play").superviseStart(player, api, probeIntervalMs = 50)

            assertEquals(StartOutcome.Playing, outcome)
        }
    }

    /** No feed to read: the budget still only starts once the server stops preparing. */
    @Test
    fun `the budget runs from when the session became ready, not from the press`() = runTest {
        val player = stalled()
        val routes = timeline(
            0L to { _ -> status("preparing") },
            5_500L to { _ -> status("ready") },
            player = player, playsAtMs = 10_000,
        )
        StubServer(routes).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")

            val outcome = watching(method = "direct_play").superviseStart(player, api, probeIntervalMs = 50)

            assertEquals(StartOutcome.Playing, outcome)
        }
    }

    @Test
    fun `a feed that goes quiet is called lost long before the ceiling`() = runTest {
        StubServer(timeline(0L to { now -> status("ready", feed(served = 1_000, idleMs = now)) })).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")

            val outcome = watching(method = "direct_play").superviseStart(stalled(), api, probeIntervalMs = 50)

            assertIs<StartOutcome.Stalled>(outcome)
            assertTrue(testScheduler.currentTime in FEED_IDLE_STALL_MS..<START_CEILING_MS, "called at ${testScheduler.currentTime}ms")
        }
    }

    @Test
    fun `a player that never asks for the stream is called on the direct budget`() = runTest {
        StubServer(statusRoute(status("ready", feed(served = 0, idleMs = null)))).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")

            val outcome = watching(method = "video_transcode").superviseStart(stalled(), api, probeIntervalMs = 50)

            assertIs<StartOutcome.Stalled>(outcome)
            assertTrue(testScheduler.currentTime < TRANSCODE_START_BUDGET_MS, "called at ${testScheduler.currentTime}ms")
        }
    }

    @Test
    fun `a stall while the server is still downloading says the source is healthy`() = runTest {
        val routes = timeline(0L to { now ->
            status("ready", feed(served = 1_000, idleMs = now, downloadBps = 30_000_000, downloaded = now * 30_000))
        })
        StubServer(routes).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")

            val outcome = watching(method = "direct_play").superviseStart(stalled(), api, probeIntervalMs = 50)

            assertIs<StartOutcome.Stalled>(outcome)
            assertTrue(outcome.sourceHealthy, "a source still downloading was blamed")
        }
    }

    @Test
    fun `the feed is described once a start has been slow for a moment`() = runTest {
        val gps = watching(method = "direct_play")
        val seen = java.util.concurrent.CopyOnWriteArrayList<String?>()
        val player = stalled()
        val routes = timeline(
            0L to { _ -> seen += gps.startProgress; status("ready", feed(served = 24_000_000, idleMs = 100)) },
            player = player, playsAtMs = 5_000,
        )
        StubServer(routes).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")

            gps.superviseStart(player, api, probeIntervalMs = 50)

            assertEquals(null, seen.first(), "described the feed before the start was slow")
            assertTrue("Buffering · 24 MB" in seen, "never described the feed: $seen")
            assertEquals(null, gps.startProgress, "left the description up after the start")
        }
    }
}
