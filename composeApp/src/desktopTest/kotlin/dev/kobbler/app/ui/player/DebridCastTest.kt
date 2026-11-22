package dev.kobbler.app.ui.player

import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.data.AudioTrackDto
import dev.kobbler.app.data.Episode
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.Season
import dev.kobbler.app.data.TvShow
import dev.kobbler.app.data.castCapabilities
import dev.kobbler.app.platform.CastLoad
import io.ktor.client.HttpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A debrid stream is the same watch as a library file, only from somewhere else: the
 * receiver gets a session made for what it can decode, at the playhead, in the language
 * the viewer was hearing. Handing it this device's own session gave it a file it could
 * not open.
 */
class DebridCastTest {

    private val show = TvShow(
        id = "show-1", title = "Show", year = 2020, posterUrl = "",
        seasons = listOf(Season(number = 1, episodes = listOf(Episode(id = "ep-1", number = 1, title = "One")))),
    )

    private val resolve = "/api/v1/admin/stream/resolve"

    private fun castFromDebrid(playsHls: Boolean, check: (load: CastLoad, asked: String, stubUrl: String) -> Unit) {
        val routes = mapOf<String, (HttpExchange) -> String>(
            "/api/v1/admin/playback/tokens" to { _ -> """{"token":"cast-tok"}""" },
            resolve to { _ ->
                """{"session_id":"cast-s","method":"video_transcode","start_time":600.0,
                "playlist_url":"/api/v1/admin/playback/sessions/cast-s/master.m3u8",
                ${if (playsHls) "" else "\"progressive_url\":\"/api/v1/admin/playback/sessions/cast-s/stream.ts\","}
                "duration_seconds":3600.0}"""
            },
            "/api/v1/admin/stream/s1/outcome" to { _ -> "{}" },
        )
        StubServer(routes).use { stub ->
            runBlocking {
                val api = KobblerApi(HttpClient(), stub.url, "token")
                val player = FakePlayer().apply { durationMs.value = 3_600_000 }
                val gps = GlobalPlayerState()
                val option = debridOption("h1")
                withTimeout(20_000) {
                    tryPlayTvWithFallback(
                        api = api, player = player, gps = gps, show = show,
                        episodeId = "ep-1", seasonIdx = 0, episodeIdx = 0,
                        search = { found(listOf(option)) },
                        picked = null,
                        onCaching = { _, _ -> error("nothing here needs caching") },
                        claim = {
                            InstantStart(
                                option,
                                KobblerApi.StreamSession(
                                    url = "${stub.url}/api/v1/admin/stream/s1/play", sessionId = "s1", method = "direct_play",
                                    audioTracks = listOf(
                                        AudioTrackDto(streamIndex = 1, language = "fre", isDefault = true),
                                        AudioTrackDto(streamIndex = 2, language = "eng", title = "English 5.1"),
                                    ),
                                    selectedAudioIndex = 1,
                                ),
                            )
                        },
                        local = { null },
                    )
                    while (!gps.isDebrid) delay(20)
                }
                gps.selectedServerAudioIndex = 2

                val load = gps.castLoad(api, castCapabilities("Projector TV"), 600, 3_600.0, playsHls = playsHls)!!

                check(load, stub.requestBodies[resolve].orEmpty(), stub.url)
            }
        }
    }

    @Test
    fun `a debrid stream is resolved again for the receiver, at the playhead and in the viewer's language`() =
        castFromDebrid(playsHls = true) { load, asked, url ->
            assertTrue("\"release_key\":\"h1\"" in asked, "not the release being watched: $asked")
            assertTrue("\"video_codecs\":[\"h264\",\"vp9\"]" in asked, "asked with this device's capabilities: $asked")
            assertTrue("\"start_time\":600" in asked, asked)
            assertTrue("\"preferred_audio_language\":\"eng\"" in asked, asked)
            assertTrue("\"preferred_audio_title\":\"English 5.1\"" in asked, asked)
            assertTrue("\"delivery\"" !in asked, "a Cast receiver plays HLS: $asked")

            assertEquals("$url/api/v1/admin/playback/sessions/cast-s/master.m3u8", load.contentUrl)
            assertEquals("application/x-mpegURL", load.contentType)
            assertEquals(0, load.startPositionSec, "the playlist already begins at the resume point")
            assertTrue("\"sessionId\":\"cast-s\"" in load.payloadJson, load.payloadJson)
        }

    @Test
    fun `a debrid stream cast to a renderer without HLS is asked for as one progressive stream`() =
        castFromDebrid(playsHls = false) { load, asked, url ->
            assertTrue("\"delivery\":\"progressive\"" in asked, asked)
            assertEquals("$url/api/v1/admin/playback/sessions/cast-s/stream.ts", load.contentUrl)
            assertEquals("video/mp2t", load.contentType)
        }
}
