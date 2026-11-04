package dev.kobbler.app.ui.player

import dev.kobbler.app.data.AudioTrackDto
import dev.kobbler.app.data.CastAudioTrack
import dev.kobbler.app.data.CastSkip
import dev.kobbler.app.data.CastTextTrack
import dev.kobbler.app.data.CastTracks
import dev.kobbler.app.data.ClientCapabilities
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
                newPosterUrl = "${api.apiUrl}/poster.jpg", newLogoUrl = null,
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
                playingState(api, "direct_play").castLoad(api, castCapabilities("Living Room TV"), 1_380, 3_600.0)
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
            // What the phone's own media controls show, fetched with no login of their own.
            assertEquals("Show", load.title)
            assertEquals("S1E1", load.subtitle)
            assertEquals("${stub.url}/poster.jpg?token=cast-tok", load.imageUrl)
        }
    }

    /** A transcode is a playlist, and the receiver has to be told to follow it as one. */
    @Test
    fun `a transcoded handover goes across as a playlist`() {
        stub().use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val load = runBlocking {
                playingState(api, "video_transcode").castLoad(api, castCapabilities("Living Room TV"), 0, 3_600.0)
            }!!

            assertEquals("application/x-mpegURL", load.contentType)
            assertTrue("\"method\":\"hls\"" in load.payloadJson, load.payloadJson)
            assertTrue("\"directUrl\":null" in load.payloadJson, load.payloadJson)
        }
    }

    /**
     * This device plays nearly anything as it is, so its session is the file itself; a
     * receiver that cannot open an AVI gets nothing from that but an error.
     */
    @Test
    fun `a library title gets a session of its own, made for what the receiver can decode`() {
        val sessions = "/api/v1/admin/playback/sessions"
        StubServer(
            mapOf(
                TOKENS to { _ -> """{"token":"cast-tok"}""" },
                sessions to { _ ->
                    """{"session_id":"cast-sess","method":"video_transcode","start_time":1380.0,
                    "playlist_url":"/api/v1/admin/playback/sessions/cast-sess/master.m3u8",
                    "duration_seconds":1392.0}"""
                },
            ),
        ).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val load = runBlocking {
                playingState(api, "direct_play").castLoad(api, castCapabilities("Projector TV"), 1_380, 1_392.0)
            }!!

            val asked = stub.requestBodies.getValue(sessions)
            assertTrue("\"media_id\":\"ep-1\"" in asked, asked)
            assertTrue("\"video_codecs\":[\"h264\",\"vp9\"]" in asked, asked)
            assertTrue("\"start_time\":1380" in asked, asked)

            assertEquals("application/x-mpegURL", load.contentType)
            assertEquals("${stub.url}/api/v1/admin/playback/sessions/cast-sess/master.m3u8", load.contentUrl)
            assertEquals(0, load.startPositionSec, "the playlist already begins at the resume point")
            val payload = load.payloadJson
            assertTrue("\"sessionId\":\"cast-sess\"" in payload, payload)
            assertTrue("\"method\":\"hls\"" in payload, payload)
            assertTrue("\"startOffset\":1380.0" in payload, payload)
            assertTrue("\"duration\":1392.0" in payload, payload)
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
                        .castLoad(api, castCapabilities("TV"), 0, 0.0),
                )
                assertNull(
                    playingState(api, "direct_play").apply { sessionId = null }
                        .castLoad(api, castCapabilities("TV"), 0, 0.0),
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
        // A transcode reaches the receiver as fMP4 through MSE, which rejects MP3 in it.
        assertEquals(listOf("aac"), castCapabilities("Projector TV").audioCodecs)
    }

    @Test
    fun `what the receiver says about itself and its tracks reads as events`() {
        assertEquals(
            CastEvent.Capabilities(
                ClientCapabilities(listOf("h264", "hevc"), listOf("aac", "ac3"), listOf("mp4"), maxHeight = 1080),
            ),
            parseCastEvent(
                """{"type":"CAPABILITIES","payload":{"video_codecs":["h264","hevc"],"audio_codecs":["aac","ac3"],"containers":["mp4"],"max_height":1080}}""",
            ),
        )
        assertEquals(
            CastEvent.TracksChanged(
                CastTracks(
                    subtitles = listOf(CastTextTrack(id = 1, language = "eng", forced = true)),
                    activeSubtitleId = 1,
                    audioTracks = listOf(CastAudioTrack(streamIndex = 2, language = "jpn", codec = "aac", channels = 6)),
                    activeAudioIndex = 2,
                ),
            ),
            parseCastEvent(
                """{"type":"TRACKS","payload":{"subtitles":[{"id":1,"language":"eng","title":null,"forced":true,"hearingImpaired":false}],"activeSubtitleId":1,"audioTracks":[{"streamIndex":2,"language":"jpn","title":null,"codec":"aac","channels":6}],"activeAudioIndex":2}}""",
            ),
        )
        assertEquals(
            CastEvent.SkipChanged(CastSkip("intro", 90.0)),
            parseCastEvent("""{"type":"SKIP_SEGMENT","payload":{"segmentType":"intro","endSeconds":90}}"""),
        )
        assertEquals(CastEvent.SkipChanged(null), parseCastEvent("""{"type":"SKIP_SEGMENT","payload":null}"""))
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
        assertEquals(
            CastEvent.EpisodeChanged(episodeId = "ep-2", showTitle = "South Park", label = "S1 E5 • Death"),
            parseCastEvent(
                """{"type":"EPISODE_CHANGED","payload":{"episodeId":"ep-2","title":"Death","seasonIdentifier":"1","episodeIdentifier":"5","showTitle":"South Park"}}""",
            ),
        )
        assertEquals(
            CastEvent.NowPlaying(
                mediaId = "ep-9", mediaType = "episode", title = "Show", subtitle = "S1 E9",
                posterUrl = "http://host/p.jpg", positionSec = 61.0, durationSec = 1_400.0, isPlaying = true,
            ),
            parseCastEvent(
                """{"type":"STATUS_RESPONSE","payload":{"currentTime":61,"duration":1400,"playing":true,
                "mediaId":"ep-9","mediaType":"episode","metadata":{"title":"Show","subtitle":"S1 E9","posterUrl":"http://host/p.jpg"}}}""",
            ),
        )
        assertNull(parseCastEvent("""{"type":"ENRICHMENT_READY"}"""))
        assertNull(parseCastEvent("not json"))
    }
}
