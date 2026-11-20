package dev.kobbler.app.platform.dlna

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.UiTimeoutMs
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.CastRouter
import dev.kobbler.app.platform.PlaybackProtocol
import dev.kobbler.app.runUiTest
import dev.kobbler.app.ui.player.CastHandoff
import dev.kobbler.app.ui.player.FakePlayer
import dev.kobbler.app.ui.player.GlobalPlayerState
import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A DLNA renderer only plays the URL it is handed. Everything the Kobbler receiver does on
 * its own — following the position, saving it, seeking a transcode, skipping the intro,
 * going on to the next episode — this device does in its place.
 */
@OptIn(ExperimentalTestApi::class)
class DlnaCastTest {

    private class Kobbler : AutoCloseable {
        val sessionRequests = CopyOnWriteArrayList<String>()
        val firstStreamProbes = AtomicInteger()
        private val sessions = AtomicInteger()
        lateinit var stub: StubServer

        private fun session(exchange: HttpExchange): String {
            val body = stub.requestBodies["/api/v1/admin/playback/sessions"].orEmpty()
            sessionRequests += body
            val start = Json.parseToJsonElement(body).jsonObject["start_time"]?.jsonPrimitive?.content ?: "null"
            val id = "s${sessions.incrementAndGet()}"
            return """{"session_id":"$id","method":"video_transcode",
                "playlist_url":"/api/v1/admin/playback/sessions/$id/master.m3u8",
                "progressive_url":"/api/v1/admin/playback/sessions/$id/stream.ts",
                "duration_seconds":1800.0,"start_time":$start,
                "subtitles":[{"track_id":"en","language":"eng","format":"vtt","url":"/subs/en.vtt","srt_url":"/api/v1/admin/playback/sessions/$id/subtitles/en.srt"}],
                "audio_tracks":[{"stream_index":1,"codec":"aac","language":"eng","is_default":true}],
                "skip_segments":[{"segment_type":"intro","start_seconds":1000.0,"end_seconds":1100.0}]}"""
        }

        init {
            stub = StubServer(
                mapOf(
                    "/api/v1/admin/playback/tokens" to { _: HttpExchange -> """{"token":"cast-tok"}""" },
                    "/api/v1/library/stats" to { _: HttpExchange -> """{"kobload_configured":false}""" },
                    "/api/v1/admin/config" to { _: HttpExchange -> "{}" },
                    "/api/v1/admin/playback/sessions" to ::session,
                    "/api/v1/admin/tv/episodes/ep-1/next" to { _: HttpExchange ->
                        """{"episode_id":"ep-2","title":"Two","season_identifier":"1","episode_identifier":"2","show_title":"Show"}"""
                    },
                    "/api/v1/admin/tv/episodes/ep-1/watch-state/position" to { _: HttpExchange -> "{}" },
                    "/api/v1/admin/tv/episodes/ep-2/watch-state/position" to { _: HttpExchange -> "{}" },
                    "/api/v1/admin/playback/sessions/s1/stream.ts" to { _: HttpExchange -> "" },
                ),
                // A session still preparing answers 503 until its source is ready.
                codeFor = { path, _ ->
                    if (path.endsWith("/s1/stream.ts") && firstStreamProbes.incrementAndGet() <= 2) 503 else null
                },
            )
        }

        fun saved(episode: String) = stub.requestBodies["/api/v1/admin/tv/episodes/$episode/watch-state/position"].orEmpty()

        override fun close() = stub.close()
    }

    private data class Casting(val kobbler: Kobbler, val tv: FakeRenderer, val gps: GlobalPlayerState)

    private fun ComposeUiTest.await(c: Casting, condition: () -> Boolean) = try {
        waitUntil(timeoutMillis = 20_000, condition = condition)
    } catch (e: Throwable) {
        throw AssertionError(
            "uris=${c.tv.uris.map { it.substringAfter("sessions/") }} state=${c.tv.state} pos=${c.tv.positionSec} " +
                "actions=${c.tv.actions.filter { !it.startsWith("Get") }} " +
                "starts=${c.kobbler.sessionRequests.map { Regex("start_time\":(\\d+)").find(it)?.groupValues?.get(1) }} cs=${c.gps.castSession}",
            e,
        )
    }

    private fun casting(test: ComposeUiTest.(Casting) -> Unit) = runUiTest {
        Kobbler().use { kobbler ->
            FakeRenderer().use { tv ->
                val api = KobblerApi(HttpClient(), kobbler.stub.url, "token")
                val player = FakePlayer().also { it.durationMs.value = 1_800_000 }
                val gps = GlobalPlayerState()
                runBlocking {
                    val url = "${kobbler.stub.url}/api/v1/admin/playback/sessions/local/direct"
                    gps.load(
                        player = player, api = api, type = "episode", id = "ep-1",
                        streamUrl = url, newSessionId = "local", newTitle = "Show", newSubtitle = "S1 E1",
                        newPosterUrl = null, newLogoUrl = null, destination = null, seekToSec = 0,
                        session = KobblerApi.StreamSession(url = url, sessionId = "local", method = "direct_play", durationSeconds = 1800.0),
                    )
                }
                player.currentMs.value = 300_000
                val dlna = DlnaBackend(HttpClient(), { tv.ssdp() }, MutableStateFlow(true), pollEvery = 100.milliseconds)
                val router = CastRouter(listOf(dlna))
                setContent { CastHandoff(router, gps, api, player) }

                val target = runBlocking { router.targets.first { it.isNotEmpty() } }.single()
                assertEquals("Fake TV", target.name)
                assertEquals(PlaybackProtocol.Dlna, target.protocol)
                runBlocking { router.connect(target) }
                waitUntil(timeoutMillis = UiTimeoutMs) { tv.uris.isNotEmpty() && tv.state == "PLAYING" }
                try {
                    test(Casting(kobbler, tv, gps))
                } finally {
                    runBlocking { router.stop() }
                }
            }
        }
    }

    @Test
    fun `load, poll, seek, skip, end and go on to the next episode`() = casting { c ->
        val (kobbler, tv, gps) = c
        assertTrue(tv.uris.last().endsWith("/api/v1/admin/playback/sessions/s1/stream.ts"), tv.uris.last())
        assertTrue("\"delivery\":\"progressive\"" in kobbler.sessionRequests.first(), kobbler.sessionRequests.first())
        assertTrue("\"start_time\":300" in kobbler.sessionRequests.first(), kobbler.sessionRequests.first())
        assertTrue("mkv" in kobbler.sessionRequests.first(), "renderer capabilities not sent: ${kobbler.sessionRequests.first()}")
        assertTrue("<dc:title>Show · S1 E1</dc:title>" in tv.metadata.last(), tv.metadata.last())
        assertTrue(
            "http-get:*:video/mp2t:DLNA.ORG_OP=00;DLNA.ORG_CI=1;DLNA.ORG_FLAGS=01700000000000000000000000000000" in tv.metadata.last(),
            tv.metadata.last(),
        )
        assertTrue(kobbler.firstStreamProbes.get() >= 3, "handed to the renderer while the session was still preparing")
        assertTrue("\"max_height\":1080" in kobbler.sessionRequests.first(), kobbler.sessionRequests.first())

        tv.positionSec = 20
        await(c) { gps.castSession?.positionMs == 320_000L }
        await(c) { "\"position_seconds\":320" in kobbler.saved("ep-1") }

        gps.onCastSeek?.invoke(900_000L)
        await(c) { tv.uris.size == 2 && tv.state == "PLAYING" }
        assertTrue(tv.uris.last().endsWith("/s2/stream.ts"), tv.uris.last())
        assertTrue("\"start_time\":900" in kobbler.sessionRequests.last())

        tv.positionSec = 100
        await(c) { tv.uris.size == 3 && tv.state == "PLAYING" }
        assertTrue("\"start_time\":1100" in kobbler.sessionRequests.last(), "intro not skipped: ${kobbler.sessionRequests.last()}")

        tv.positionSec = 690
        await(c) { gps.castSession?.positionMs == 1_790_000L }
        tv.state = "STOPPED"
        await(c) { gps.castSession?.mediaId == "ep-2" }
        assertTrue(tv.uris.last().endsWith("/s4/stream.ts"), tv.uris.last())
        assertTrue("\"media_id\":\"ep-2\"" in kobbler.sessionRequests.last())
        assertEquals("S1 E2 • Two", gps.castSession?.subtitle)
        await(c) { "\"position_seconds\":1800" in kobbler.saved("ep-1") }
    }

    @Test
    fun `tracks switch by reopening at the same spot, and a stop at the TV hands back here`() = casting { c ->
        val (kobbler, tv, gps) = c
        tv.positionSec = 20
        await(c) { gps.castSession?.positionMs == 320_000L }
        await(c) { gps.castSession?.tracks?.subtitles?.isNotEmpty() == true }
        assertEquals("eng", gps.castSession?.tracks?.subtitles?.single()?.language)

        gps.onCastSubtitle?.invoke(0)
        await(c) { tv.uris.size == 2 && tv.state == "PLAYING" }
        assertTrue("\"start_time\":320" in kobbler.sessionRequests.last(), kobbler.sessionRequests.last())
        assertTrue(
            """<sec:CaptionInfoEx sec:type="srt">${kobbler.stub.url}/api/v1/admin/playback/sessions/s2/subtitles/en.srt?offset=320.0</sec:CaptionInfoEx>""" in tv.metadata.last(),
            tv.metadata.last(),
        )
        await(c) { gps.castSession?.tracks?.activeSubtitleId == 0 }

        tv.positionSec = 10
        await(c) { gps.castSession?.positionMs == 330_000L }
        tv.state = "STOPPED"
        await(c) { gps.castSession == null }
        await(c) { "/api/v1/admin/playback/sessions/s2" in kobbler.stub.requestBodies }
    }
}
