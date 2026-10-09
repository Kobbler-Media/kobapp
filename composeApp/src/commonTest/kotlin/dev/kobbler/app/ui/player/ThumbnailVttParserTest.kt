package dev.kobbler.app.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals

class ThumbnailVttParserTest {

    @Test
    fun parsesSpriteSheetVttWithXywhFragments() {
        val vtt = """
            WEBVTT

            00:00:00.000 --> 00:00:10.000
            sprite_001.jpg#xywh=0,0,320,180

            00:00:10.000 --> 00:00:20.000
            sprite_001.jpg#xywh=320,0,320,180

            00:00:20.000 --> 00:00:30.000
            sprite_001.jpg#xywh=640,0,320,180
        """.trimIndent()

        val cues = parseThumbnailVttText(vtt, "https://example.com/thumbs/")

        assertEquals(3, cues.size)

        // First cue: top-left tile
        assertEquals(0L, cues[0].startMs)
        assertEquals(10_000L, cues[0].endMs)
        assertEquals("https://example.com/thumbs/sprite_001.jpg", cues[0].url)
        assertEquals(0, cues[0].cropX)
        assertEquals(0, cues[0].cropY)
        assertEquals(320, cues[0].cropW)
        assertEquals(180, cues[0].cropH)

        // Second cue: second tile in first row
        assertEquals(10_000L, cues[1].startMs)
        assertEquals(20_000L, cues[1].endMs)
        assertEquals("https://example.com/thumbs/sprite_001.jpg", cues[1].url)
        assertEquals(320, cues[1].cropX)
        assertEquals(0, cues[1].cropY)

        // Third cue
        assertEquals(640, cues[2].cropX)
    }

    @Test
    fun parsesIndividualImageUrlsWithoutCrop() {
        val vtt = """
            WEBVTT

            00:00:00.000 --> 00:00:10.000
            thumb_001.jpg

            00:00:10.000 --> 00:00:20.000
            thumb_002.jpg
        """.trimIndent()

        val cues = parseThumbnailVttText(vtt, "https://example.com/thumbs/")

        assertEquals(2, cues.size)
        assertEquals("https://example.com/thumbs/thumb_001.jpg", cues[0].url)
        assertEquals(0, cues[0].cropW)
        assertEquals(0, cues[0].cropH)

        assertEquals("https://example.com/thumbs/thumb_002.jpg", cues[1].url)
    }

    @Test
    fun resolvesAbsoluteUrls() {
        val vtt = """
            WEBVTT

            00:00:00.000 --> 00:00:10.000
            https://cdn.example.com/sprite.jpg#xywh=0,0,320,180
        """.trimIndent()

        val cues = parseThumbnailVttText(vtt, "https://example.com/thumbs/")

        assertEquals(1, cues.size)
        assertEquals("https://cdn.example.com/sprite.jpg", cues[0].url)
        assertEquals(320, cues[0].cropW)
    }

    @Test
    fun resolvesServerAbsoluteUrls() {
        val vtt = """
            WEBVTT

            00:00:00.000 --> 00:00:10.000
            /api/thumb.jpg#xywh=0,0,320,180
        """.trimIndent()

        val cues = parseThumbnailVttText(vtt, "https://example.com:8080/thumbs/")

        assertEquals(1, cues.size)
        assertEquals("https://example.com:8080/api/thumb.jpg", cues[0].url)
    }

    // kobbler serves sprites at `.../thumbnails/sprite_NNN.jpg` while the VTT
    // sits at `.../thumbnails.vtt`. The base derived from the VTT URL has to
    // include the `thumbnails/` segment or every sprite request 404s.
    @Test
    fun spriteUrlsResolveUnderThumbnailsDirectory() {
        val vttUrl = "https://kobbler.example.com/api/v1/admin/playback/sessions/abc-123/thumbnails.vtt"
        val baseUrl = thumbnailVttBaseUrl(vttUrl)

        assertEquals(
            "https://kobbler.example.com/api/v1/admin/playback/sessions/abc-123/thumbnails/",
            baseUrl,
        )

        val vtt = """
            WEBVTT

            00:00:00.000 --> 00:00:10.000
            sprite_001.jpg#xywh=0,0,320,180
        """.trimIndent()
        val cues = parseThumbnailVttText(vtt, baseUrl)

        assertEquals(1, cues.size)
        assertEquals(
            "https://kobbler.example.com/api/v1/admin/playback/sessions/abc-123/thumbnails/sprite_001.jpg",
            cues[0].url,
        )
    }
}
