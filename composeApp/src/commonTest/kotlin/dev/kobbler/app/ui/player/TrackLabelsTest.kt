package dev.kobbler.app.ui.player

import dev.kobbler.app.data.AudioTrackDto
import dev.kobbler.app.data.PlaybackSubtitleTrackDto
import dev.kobbler.kobplayer.TrackInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class TrackLabelsTest {

    @Test
    fun `untagged audio tracks are numbered from one, not by stream index`() {
        val tracks = listOf(AudioTrackDto(streamIndex = 1, codec = "eac3", channels = 6), AudioTrackDto(streamIndex = 2, codec = "eac3", channels = 6))
        assertEquals(listOf("Track 1 (eac3) 5.1", "Track 2 (eac3) 5.1"), tracks.audioTrackInfos().map { it.label })
        assertEquals(listOf("1", "2"), tracks.audioTrackInfos().map { it.id })
    }

    @Test
    fun `tagged audio tracks keep their title or language`() {
        val tracks = listOf(AudioTrackDto(streamIndex = 1, language = "eng", title = "Commentary"), AudioTrackDto(streamIndex = 2, language = "jpn", languageName = "Japanese"))
        assertEquals(listOf("Commentary Stereo", "Japanese Stereo"), tracks.audioTrackInfos().map { it.label })
    }

    @Test
    fun `untagged subtitle tracks are told apart`() {
        val subs = listOf(PlaybackSubtitleTrackDto(trackId = "a"), PlaybackSubtitleTrackDto(trackId = "b", isDefault = true), PlaybackSubtitleTrackDto(trackId = "c", language = "eng", languageName = "English"))
        assertEquals(listOf("Track 1", "Track 2 · default", "English"), subs.subtitleTrackInfos().map { it.label })
    }

    @Test
    fun `forced and SDH tracks say so`() {
        val subs = listOf(
            PlaybackSubtitleTrackDto(trackId = "a", language = "eng", languageName = "English", isForced = true),
            PlaybackSubtitleTrackDto(trackId = "b", language = "eng", languageName = "English", isHearingImpaired = true),
        )
        assertEquals(listOf("English · forced", "English · SDH"), subs.subtitleTrackInfos().map { it.label })
    }

    @Test
    fun `the player's own tracks take the server's names when they line up`() {
        val vlc = listOf(TrackInfo("t1", "Track 1"), TrackInfo("t2", "Track 2"))
        val server = listOf(
            PlaybackSubtitleTrackDto(streamIndex = 4, language = "fre", languageName = "French", isForced = true),
            PlaybackSubtitleTrackDto(streamIndex = 3, language = "eng", languageName = "English"),
        )
        val named = vlc.labelledBy(server)
        assertEquals(listOf("English", "French · forced"), named.map { it.label })
        assertEquals(listOf("t1", "t2"), named.map { it.id }, "selection still goes by the player's own ids")
        assertEquals(vlc, vlc.labelledBy(server.take(1)), "a list that does not line up is not guessed at")
    }

    @Test
    fun `a title that names nothing gives way to the language`() {
        val subs = listOf(
            PlaybackSubtitleTrackDto(trackId = "a", title = "Track 3", language = "eng", languageName = "English"),
            PlaybackSubtitleTrackDto(trackId = "b", title = "SubRip", language = "fre", languageName = "French"),
            PlaybackSubtitleTrackDto(trackId = "c", title = "English (Commentary)", language = "eng"),
            PlaybackSubtitleTrackDto(trackId = "d", title = "Subtitle", language = null),
        )
        assertEquals(listOf("English", "French", "English (Commentary)", "Subtitle"), subs.subtitleTrackInfos().map { it.label })
    }

    @Test
    fun `a title that names a variant keeps the language beside it`() {
        val subs = listOf(
            PlaybackSubtitleTrackDto(trackId = "a", title = "European", language = "spa", languageName = "Spanish"),
            PlaybackSubtitleTrackDto(trackId = "b", title = "Latin American", language = "spa", languageName = "Spanish"),
            PlaybackSubtitleTrackDto(trackId = "c", title = "SDH", language = "eng", languageName = "English", isHearingImpaired = true),
            PlaybackSubtitleTrackDto(trackId = "d", title = "Spanish (Castilian)", language = "spa"),
        )
        assertEquals(
            listOf("Spanish · European", "Spanish · Latin American", "English · SDH", "Spanish (Castilian)"),
            subs.subtitleTrackInfos().map { it.label },
        )
    }

    @Test
    fun `a language the server could not name shows as its code`() {
        val subs = listOf(PlaybackSubtitleTrackDto(trackId = "a", language = "xyz"), PlaybackSubtitleTrackDto(trackId = "b", language = "pt-BR"))
        assertEquals(listOf("XYZ", "PT-BR"), subs.subtitleTrackInfos().map { it.label })
        assertEquals(listOf("XYZ Stereo"), listOf(AudioTrackDto(streamIndex = 1, language = "xyz")).audioTrackInfos().map { it.label })
    }
}
