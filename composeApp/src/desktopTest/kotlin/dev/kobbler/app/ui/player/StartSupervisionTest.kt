package dev.kobbler.app.ui.player

import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.data.KobblerApi
import io.ktor.client.HttpClient
import kotlinx.coroutines.test.runTest
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

            assertEquals(StartOutcome.Stalled, outcome)
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

            assertEquals(StartOutcome.Stalled, outcome)
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

            assertEquals(StartOutcome.Stalled, outcome)
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

            assertEquals(StartOutcome.Stalled, outcome)
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

        assertEquals(StartOutcome.Stalled, outcome, "read a failed request as a failed session")
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
}
