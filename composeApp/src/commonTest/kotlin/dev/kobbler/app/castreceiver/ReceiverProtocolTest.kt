package dev.kobbler.app.castreceiver

import dev.kobbler.app.data.CastAudioTrack
import dev.kobbler.app.data.CastInitSession
import dev.kobbler.app.data.CastMetadata
import dev.kobbler.app.data.CastSkip
import dev.kobbler.app.data.CastTextTrack
import dev.kobbler.app.data.CastTracks
import dev.kobbler.app.data.ClientCapabilities
import dev.kobbler.app.data.PlaybackSubtitleTrackDto
import dev.kobbler.app.platform.CastEvent
import dev.kobbler.app.platform.CastFeature
import dev.kobbler.app.platform.parseCastEvent
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * kobapp on a TV answers the same phone the web receiver does, so every reply has to read
 * back through the phone's own [parseCastEvent] and every command the phone sends has to
 * be understood.
 */
class ReceiverProtocolTest {

    private val payloadJson = Json { encodeDefaults = true; explicitNulls = true }

    private fun session(mediaType: String = "movie", startOffset: Double = 0.0) = CastInitSession(
        baseUrl = "http://phone-server:8080",
        token = "sender-tok",
        sessionId = "phone-cast-session",
        mediaId = "movie-1",
        mediaType = mediaType,
        playlistUrl = null,
        directUrl = "http://phone-server:8080/api/v1/admin/playback/sessions/phone-cast-session/direct",
        method = "direct_play",
        duration = 7_200.0,
        capabilities = ClientCapabilities(listOf("h264"), listOf("aac"), listOf("mp4")),
        metadata = CastMetadata(title = "Film", subtitle = "2024", posterUrl = "http://phone-server:8080/poster.jpg"),
        startOffset = startOffset,
    )

    private fun customData(session: CastInitSession) =
        """{"kobblerSession":${payloadJson.encodeToString(session)}}"""

    @Test
    fun `a load opens the sender's title on the sender's server with the sender's token`() {
        val load = parseReceiverLoad(customData(session()), startSec = 1_380.0)!!
        assertEquals("http://phone-server:8080", load.session.baseUrl)
        assertEquals("sender-tok", load.session.token)
        assertEquals(ReceiverTitle("movie", "movie-1", "Film", "2024", "http://phone-server:8080/poster.jpg"), load.title)
        assertEquals(1_380, load.positionSec)
    }

    /** The phone sends a transcode's position relative to where that transcode began. */
    @Test
    fun `a load from a rebased transcode is positioned in title time`() {
        val load = parseReceiverLoad(customData(session(startOffset = 1_200.0)), startSec = 180.0)!!
        assertEquals(1_380, load.positionSec)
    }

    @Test
    fun `a load the receiver cannot play is refused`() {
        assertNull(parseReceiverLoad(null, 0.0))
        assertNull(parseReceiverLoad("""{"somethingElse":{}}""", 0.0))
        assertNull(parseReceiverLoad(customData(session(mediaType = "live")), 0.0))
        assertNull(parseReceiverLoad(customData(session().copy(mediaId = null)), 0.0))
    }

    @Test
    fun `commands from the phone are understood`() {
        val cases = mapOf(
            """{"type":"SUBTITLE_CHANGE","payload":{"trackId":2}}""" to ReceiverCommand.SubtitleChange(2),
            """{"type":"SUBTITLE_CHANGE","payload":{"trackId":null}}""" to ReceiverCommand.SubtitleChange(null),
            """{"type":"AUDIO_SWITCH","payload":{"streamIndex":3}}""" to ReceiverCommand.AudioSwitch(3),
            """{"type":"TOKEN_REFRESH","payload":{"token":"fresh"}}""" to ReceiverCommand.TokenRefresh("fresh"),
            """{"type":"STATUS_REQUEST"}""" to ReceiverCommand.StatusRequest,
            """{"type":"CAPABILITIES_REQUEST"}""" to ReceiverCommand.CapabilitiesRequest,
            """{"type":"TRACKS_REQUEST"}""" to ReceiverCommand.TracksRequest,
            """{"type":"INIT_AUDIOBOOK","payload":{}}""" to ReceiverCommand.Unplayable("INIT_AUDIOBOOK"),
            """{"type":"INIT_TTS","payload":{}}""" to ReceiverCommand.Unplayable("INIT_TTS"),
            """{"type":"TTS_STOP","payload":{}}""" to ReceiverCommand.Unsupported("TTS_STOP"),
        )
        cases.forEach { (json, expected) -> assertEquals(expected, parseReceiverCommand(json), json) }
    }

    /** The LOAD carries the same payload, and is what opens the title. */
    @Test
    fun `init session on the namespace and garbage are ignored`() {
        assertNull(parseReceiverCommand("""{"type":"INIT_SESSION","payload":{}}"""))
        assertNull(parseReceiverCommand("not json"))
        assertNull(parseReceiverCommand("""{"type":"AUDIO_SWITCH","payload":{}}"""))
    }

    private val now = ReceiverNow(
        mediaType = "episode", mediaId = "ep-1",
        title = "Show", subtitle = "S1 E1", posterUrl = "http://p/poster.jpg",
        positionMs = 600_500, durationMs = 2_400_000, playing = true,
    )

    @Test
    fun `replies read back through the phone's parser`() {
        val tracks = CastTracks(
            subtitles = listOf(CastTextTrack(1, "en", "English", null, forced = false, hearingImpaired = true)),
            activeSubtitleId = 1,
            audioTracks = listOf(CastAudioTrack(1, "ja", "Japanese", "Stereo", "aac", 2)),
            activeAudioIndex = 1,
        )
        val caps = ClientCapabilities(listOf("h264", "hevc"), listOf("aac", "eac3"), listOf("mp4", "mkv"), hdrFormats = listOf("hdr10"))
        val cases = listOf(
            ReceiverReplies.position(now) to CastEvent.Position(600.5, 2_400.0, isPlaying = true),
            ReceiverReplies.status(now) to CastEvent.NowPlaying(
                mediaId = "ep-1", mediaType = "episode", title = "Show", subtitle = "S1 E1",
                posterUrl = "http://p/poster.jpg", positionSec = 600.5, durationSec = 2_400.0, isPlaying = true,
            ),
            ReceiverReplies.capabilities(caps, CastConnectFeatures) to CastEvent.Capabilities(caps, CastConnectFeatures),
            ReceiverReplies.refused("INIT_AUDIOBOOK") to CastEvent.Refused(CastFeature.Audiobook),
            ReceiverReplies.refused("INIT_TTS") to CastEvent.Refused(CastFeature.Tts),
            ReceiverReplies.tracks(tracks) to CastEvent.TracksChanged(tracks),
            ReceiverReplies.episodeChanged(
                NextEpisode(episodeId = "ep-2", title = "Pilot II", episodeIdentifier = "2", seasonIdentifier = "1", showTitle = "Show"),
            ) to CastEvent.EpisodeChanged("ep-2", "Show", "S1 E2 • Pilot II"),
            ReceiverReplies.skip(CastSkip("intro", 95.0)) to CastEvent.SkipChanged(CastSkip("intro", 95.0)),
            ReceiverReplies.skip(null) to CastEvent.SkipChanged(null),
            ReceiverReplies.finished to CastEvent.Finished,
            ReceiverReplies.expired to CastEvent.Expired,
        )
        cases.forEach { (json, expected) -> assertEquals(expected, parseCastEvent(json), json) }
    }

    @Test
    fun `the web receiver's capabilities list no features, and unknown ones are passed over`() {
        val caps = ClientCapabilities(listOf("h264"), listOf("aac"), listOf("mp4"))
        val web = """{"type":"CAPABILITIES","payload":{"video_codecs":["h264"],"audio_codecs":["aac"],"containers":["mp4"]}}"""
        assertEquals(CastEvent.Capabilities(caps, features = null), parseCastEvent(web))
        val newer = """{"type":"CAPABILITIES","payload":{"video_codecs":["h264"],"audio_codecs":["aac"],"containers":["mp4"],"features":["hls","teleport"]}}"""
        assertEquals(CastEvent.Capabilities(caps, setOf(CastFeature.Hls)), parseCastEvent(newer))
        assertNull(parseCastEvent("""{"type":"UNSUPPORTED","payload":{"type":"TTS_STOP"}}"""))
    }

    private fun sub(id: String) = PlaybackSubtitleTrackDto(trackId = id, language = "en", url = "http://s/$id.vtt", format = "vtt")

    @Test
    fun `subtitles are numbered as the phone picks them`() {
        val server = listOf(sub("a"), sub("b"))
        val tracks = castTracks(server, selectedSubtitleTrackId = "b", audio = emptyList(), selectedAudioIndex = null)
        assertEquals(listOf(1, 2), tracks.subtitles.map { it.id })
        assertEquals(2, tracks.activeSubtitleId)
        assertEquals("b", subtitleTrackIdFor(2, server))
        assertNull(subtitleTrackIdFor(null, server))
        assertNull(subtitleTrackIdFor(9, server))
    }
}
