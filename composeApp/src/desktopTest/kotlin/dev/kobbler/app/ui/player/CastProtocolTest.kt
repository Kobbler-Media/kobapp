package dev.kobbler.app.ui.player

import dev.kobbler.app.data.AudioTrackDto
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.PlaybackSubtitleTrackDto
import dev.kobbler.app.data.SkipSegment
import dev.kobbler.app.data.castCapabilities
import dev.kobbler.app.platform.CastEvent
import dev.kobbler.app.platform.parseCastEvent
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TOKENS = "/api/v1/admin/playback/tokens"

/**
 * A receiver plays the stream, but it also acts as a client in its own right — it saves
 * the position, follows the skip segments and fetches the next episode. Everything it
 * needs for that has to be in the handover, because it cannot ask this device anything.
 */
class CastProtocolTest {

    private fun stub() = StubServer(
        mapOf(TOKENS to { _: com.sun.net.httpserver.HttpExchange -> """{"token":"cast-tok"}""" }),
    )

    private val streamUrl = "http://host/api/v1/admin/playback/sessions/sess-1/stream"

    /** Loaded the way a screen loads it, so the handover reads the state a watch leaves. */
    private fun playingState(api: KobblerApi, method: String): GlobalPlayerState {
        val gps = GlobalPlayerState()
        runBlocking {
            gps.load(
                player = FakePlayer(), api = api,
                type = "episode", id = "ep-1",
                streamUrl = streamUrl, newSessionId = "sess-1",
                newTitle = "Show", newSubtitle = "S1E1",
                newPosterUrl = "http://host/poster.jpg", newLogoUrl = null,
                destination = null,
                session = KobblerApi.StreamSession(
                    url = streamUrl,
                    sessionId = "sess-1",
                    method = method,
                    thumbnailsUrl = "http://host/thumbs.vtt",
                    skipSegments = listOf(
                        SkipSegment(segmentType = "intro", startSeconds = 30.0, endSeconds = 90.0),
                    ),
                    audioTracks = listOf(AudioTrackDto(streamIndex = 1, codec = "eac3")),
                    subtitles = listOf(
                        PlaybackSubtitleTrackDto(
                            trackId = "t1", url = "http://host/subs/t1.vtt", format = "vtt",
                        ),
                    ),
                ),
            )
        }
        return gps
    }

    @Test
    fun `a direct-played handover carries the file and the session behind it`() {
        stub().use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val load = runBlocking {
                playingState(api, "direct_play").castLoad(api, "Living Room TV", 1_380, 3_600.0)
            }!!

            assertEquals("video/mp4", load.contentType)
            assertEquals(1_380, load.startPositionSec)
            assertEquals(
                "http://host/api/v1/admin/playback/sessions/sess-1/stream",
                load.contentUrl,
            )
            val payload = load.payloadJson
            assertTrue("\"method\":\"direct_play\"" in payload, payload)
            assertTrue("\"directUrl\":\"http://host/api" in payload, payload)
            assertTrue("\"playlistUrl\":null" in payload, payload)
            // The receiver holds no login of its own.
            assertTrue("\"token\":\"cast-tok\"" in payload, payload)
            assertTrue("\"baseUrl\":\"${stub.url}\"" in payload, payload)
            // Server-shaped, because the receiver reads these as the server wrote them.
            assertTrue("\"segment_type\":\"intro\"" in payload, payload)
            assertTrue("\"start_seconds\":30.0" in payload, payload)
            assertTrue("\"duration\":3600.0" in payload, payload)
            assertTrue("\"title\":\"Show\"" in payload, payload)
        }
    }

    /** A transcode is a playlist, and the receiver has to be told to follow it as one. */
    @Test
    fun `a transcoded handover goes across as a playlist`() {
        stub().use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val load = runBlocking {
                playingState(api, "video_transcode").castLoad(api, "Living Room TV", 0, 3_600.0)
            }!!

            assertEquals("application/x-mpegURL", load.contentType)
            assertTrue("\"method\":\"hls\"" in load.payloadJson, load.payloadJson)
            assertTrue("\"directUrl\":null" in load.payloadJson, load.payloadJson)
        }
    }

    /** Live is this device's tuner, and a title with no session is already gone. */
    @Test
    fun `there is nothing to hand over without a session`() {
        stub().use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            runBlocking {
                assertNull(
                    playingState(api, "direct_play").apply { mediaType = "livetv" }
                        .castLoad(api, "TV", 0, 0.0),
                )
                assertNull(
                    playingState(api, "direct_play").apply { sessionId = null }
                        .castLoad(api, "TV", 0, 0.0),
                )
            }
        }
    }

    /**
     * A device that cannot decode what it is sent shows a black screen, and the receiver
     * passes this on when it mints sessions of its own.
     */
    @Test
    fun `codec support is read off the device name`() {
        assertEquals(listOf("h264", "vp9"), castCapabilities("Kitchen speaker").videoCodecs)
        assertTrue("hevc" in castCapabilities("Chromecast Ultra").videoCodecs)
        assertTrue("hevc" in castCapabilities("Lounge Google TV").videoCodecs)
        assertTrue("eac3" in castCapabilities("Lounge Google TV").audioCodecs)
        assertTrue("eac3" !in castCapabilities("Chromecast Ultra").audioCodecs)
    }

    /** The receiver broadcasts to every sender attached to it, most of it for the web. */
    @Test
    fun `only what this client can act on reads as an event`() {
        assertEquals(
            CastEvent.Position(positionSec = 1_400.5, durationSec = 3_600.0, isPlaying = true),
            parseCastEvent(
                """{"type":"POSITION_UPDATE","payload":{"currentTime":1400.5,"duration":3600,"playing":true}}""",
            ),
        )
        assertEquals(
            CastEvent.Position(positionSec = 12.0, durationSec = 0.0, isPlaying = false),
            parseCastEvent("""{"type":"STATUS_RESPONSE","payload":{"currentTime":12}}"""),
        )
        assertEquals(CastEvent.Finished, parseCastEvent("""{"type":"MEDIA_FINISHED"}"""))
        assertEquals(CastEvent.Expired, parseCastEvent("""{"type":"SESSION_EXPIRED"}"""))
        assertNull(parseCastEvent("""{"type":"EPISODE_CHANGED","payload":{"episodeId":"ep-2"}}"""))
        assertNull(parseCastEvent("not json"))
    }
}
