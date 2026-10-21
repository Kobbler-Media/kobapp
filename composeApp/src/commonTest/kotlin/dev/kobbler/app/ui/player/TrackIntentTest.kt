package dev.kobbler.app.ui.player

import dev.kobbler.app.data.AudioTrackDto
import dev.kobbler.app.data.PlaybackSubtitleTrackDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackIntentTest {

    private fun audio(vararg pairs: Pair<Int, String?>) =
        pairs.map { (idx, lang) -> AudioTrackDto(streamIndex = idx, language = lang) }

    private fun embedded(vararg idx: Int) =
        idx.map { PlaybackSubtitleTrackDto(streamIndex = it, format = "subrip") }

    /** The common case by a distance: the server picked no subtitle, so none may be drawn. */
    @Test
    fun `no subtitle picked means subtitles off`() {
        val intent = trackIntent(audio(1 to "eng"), 1, embedded(2, 3), null)
        assertTrue(intent.subtitlesOff)
        assertNull(intent.subtitleOrdinal)
    }

    /** A rendered sidecar is already on screen; a container track on top would double it. */
    @Test
    fun `a server-rendered sidecar leaves the container's own tracks alone`() {
        val subs = listOf(PlaybackSubtitleTrackDto(streamIndex = 2, url = "/s.vtt", format = "vtt"))
        val intent = trackIntent(audio(1 to "eng"), 1, subs, 2)
        assertTrue(intent.subtitlesOff)
    }

    /** Ordinal within the subtitle streams, counting from zero — not the container index. */
    @Test
    fun `an embedded subtitle is named by its ordinal among subtitles`() {
        val intent = trackIntent(audio(1 to "eng"), 1, embedded(3, 5, 7), 7)
        assertEquals(2, intent.subtitleOrdinal)
        assertTrue(!intent.subtitlesOff)
    }

    @Test
    fun `subtitle ordinals are counted in stream order whatever order they arrive in`() {
        val subs = embedded(7, 3, 5)
        assertEquals(1, trackIntent(audio(1 to "eng"), 1, subs, 5).subtitleOrdinal)
    }

    @Test
    fun `a language that names one track is used`() {
        val intent = trackIntent(audio(1 to "eng", 2 to "jpn"), 2, emptyList(), null)
        assertEquals("jpn", intent.audioLanguage)
    }

    /**
     * Two tracks in the same language cannot be told apart by language, and guessing gets
     * the commentary instead of the film. Left to the reconcile, which can see the real
     * track list and pick by position.
     */
    @Test
    fun `an ambiguous language is not used`() {
        val intent = trackIntent(audio(1 to "eng", 2 to "eng"), 2, emptyList(), null)
        assertNull(intent.audioLanguage)
    }

    /** One track is already the one that will play; naming it risks a mismatch for nothing. */
    @Test
    fun `a sole audio track is left alone`() {
        assertNull(trackIntent(audio(1 to "eng"), 1, emptyList(), null).audioLanguage)
    }

    @Test
    fun `a track with no language is left alone`() {
        val intent = trackIntent(audio(1 to null, 2 to null), 2, emptyList(), null)
        assertNull(intent.audioLanguage)
    }

    private fun tracks(vararg pairs: Pair<Int, String>, default: Int) =
        pairs.map { (idx, lang) -> AudioTrackDto(streamIndex = idx, language = lang, isDefault = idx == default) }

    @Test
    fun `the start track follows the server, then the preferred language, then the file's default`() {
        val italianFirst = tracks(1 to "ita", 2 to "eng", default = 1)
        val shortCodes = tracks(1 to "it", 2 to "en", default = 1)
        data class Case(val name: String, val tracks: List<AudioTrackDto>, val server: Int?, val preferred: String?, val expected: Int?)
        listOf(
            Case("server pick wins", italianFirst, 1, "eng", 1),
            Case("preferred language over the default", italianFirst, null, "eng", 2),
            Case("ISO 639-1 tracks match a 639-2 preference", shortCodes, null, "eng", 2),
            Case("no match keeps the default", italianFirst, null, "jpn", 1),
            Case("no preference keeps the default", italianFirst, null, null, 1),
            Case("no tracks, nothing to pick", emptyList(), null, "eng", null),
        ).forEach { c ->
            assertEquals(c.expected, startAudioIndex(c.tracks, c.server, c.preferred), c.name)
        }
    }
}
