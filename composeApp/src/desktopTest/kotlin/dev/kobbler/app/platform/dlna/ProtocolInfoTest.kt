package dev.kobbler.app.platform.dlna

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A renderer's Sink list is what the server is told it can decode. */
class ProtocolInfoTest {

    private data class Case(val name: String, val sink: String, val video: Set<String>, val audio: Set<String>, val containers: Set<String>)

    @Test
    fun `sink MIME types and DLNA profiles map to capabilities`() {
        listOf(
            Case(
                "nothing listed falls back to the DLNA baseline",
                "",
                setOf("h264"), setOf("aac"), setOf("mp4", "ts"),
            ),
            Case(
                "a Samsung-style list",
                "http-get:*:video/mp4:DLNA.ORG_PN=AVC_MP4_HP_HD_AAC,http-get:*:video/x-matroska:*," +
                    "http-get:*:video/mpeg:DLNA.ORG_PN=AVC_TS_HD_AC3;DLNA.ORG_OP=01,http-get:*:audio/mpeg:*,http-get:*:video/hevc:*",
                setOf("h264", "hevc", "mpeg2video"), setOf("aac", "ac3", "mp3"), setOf("mp4", "ts", "mkv", "matroska"),
            ),
            Case(
                "profiles alone name codecs",
                "http-get:*:video/vnd.dlna.mpeg-tts:DLNA.ORG_PN=HEVC_TS_HD_EAC3_T",
                setOf("h264", "hevc"), setOf("aac", "ac3", "eac3"), setOf("mp4", "ts"),
            ),
            Case(
                "audio-only renderer still gets the video baseline",
                "http-get:*:audio/flac:*,http-get:*:audio/mpeg:*",
                setOf("h264"), setOf("aac", "flac", "mp3"), setOf("mp4", "ts"),
            ),
        ).forEach { case ->
            val caps = capabilitiesFromSink(case.sink)
            assertEquals(case.video, caps.videoCodecs.toSet(), "${case.name}: video")
            assertEquals(case.audio, caps.audioCodecs.toSet(), "${case.name}: audio")
            assertEquals(case.containers, caps.containers.toSet(), "${case.name}: containers")
            assertEquals(1080, caps.maxHeight, "${case.name}: a renderer is sent nothing taller")
            assertEquals(41, caps.maxH264Level, "${case.name}: a renderer is sent no H.264 above level 4.1")
        }
    }

    @Test
    fun `a real Samsung Frame sink list keeps H264 to the level its AVC profiles stop at`() {
        val sink = javaClass.getResource("/dlna/samsung-frame-2022-sink.txt")!!.readText().trim()
        val caps = capabilitiesFromSink(sink)
        assertEquals(41, caps.maxH264Level)
        assertTrue("hevc" in caps.videoCodecs)
        assertTrue("ac3" in caps.audioCodecs)
        assertTrue("mkv" in caps.containers)
    }

    @Test
    fun `the DIDL item carries the sidecar both ways renderers look for it`() {
        val didl = didlLite("Film & Co", "http://s/stream.ts?a=1&b=2", "http-get:*:video/mp2t:*", "http://s/p.jpg?token=t", "http://s/1.srt")
        assertTrue("<dc:title>Film &amp; Co</dc:title>" in didl)
        assertTrue("""protocolInfo="http-get:*:video/mp2t:*">http://s/stream.ts?a=1&amp;b=2</res>""" in didl)
        assertTrue("""<sec:CaptionInfoEx sec:type="srt">http://s/1.srt</sec:CaptionInfoEx>""" in didl)
        assertTrue("""protocolInfo="http-get:*:text/srt:*">http://s/1.srt</res>""" in didl)
        assertTrue("<upnp:albumArtURI>http://s/p.jpg?token=t</upnp:albumArtURI>" in didl)
    }
}
