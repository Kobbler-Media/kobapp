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
        val tracks = listOf(AudioTrackDto(streamIndex = 1, language = "eng", title = "Commentary"), AudioTrackDto(streamIndex = 2, language = "jpn"))
        assertEquals(listOf("Commentary Stereo", "jpn Stereo"), tracks.audioTrackInfos().map { it.label })
    }

    @Test
    fun `untagged subtitle tracks are told apart`() {
        val subs = listOf(PlaybackSubtitleTrackDto(trackId = "a"), PlaybackSubtitleTrackDto(trackId = "b", isDefault = true), PlaybackSubtitleTrackDto(trackId = "c", language = "eng"))
        assertEquals(listOf("Track 1", "Track 2 · default", "ENG"), subs.subtitleTrackInfos().map { it.label })
    }

    @Test
    fun `forced and SDH tracks say so`() {
        val subs = listOf(
            PlaybackSubtitleTrackDto(trackId = "a", language = "eng", isForced = true),
            PlaybackSubtitleTrackDto(trackId = "b", language = "eng", isHearingImpaired = true),
        )
        assertEquals(listOf("ENG · forced", "ENG · SDH"), subs.subtitleTrackInfos().map { it.label })
    }

    @Test
    fun `the player's own tracks take the server's names when they line up`() {
        val vlc = listOf(TrackInfo("t1", "Track 1"), TrackInfo("t2", "Track 2"))
        val server = listOf(
            PlaybackSubtitleTrackDto(streamIndex = 4, language = "fre", isForced = true),
            PlaybackSubtitleTrackDto(streamIndex = 3, language = "eng"),
        )
        val named = vlc.labelledBy(server)
        assertEquals(listOf("ENG", "FRE · forced"), named.map { it.label })
        assertEquals(listOf("t1", "t2"), named.map { it.id }, "selection still goes by the player's own ids")
        assertEquals(vlc, vlc.labelledBy(server.take(1)), "a list that does not line up is not guessed at")
    }

    @Test
    fun `a title that names nothing gives way to the language`() {
        val subs = listOf(
            PlaybackSubtitleTrackDto(trackId = "a", title = "Track 3", language = "eng"),
            PlaybackSubtitleTrackDto(trackId = "b", title = "SubRip", language = "fre"),
            PlaybackSubtitleTrackDto(trackId = "c", title = "English (Commentary)", language = "eng"),
            PlaybackSubtitleTrackDto(trackId = "d", title = "Subtitle", language = null),
        )
        assertEquals(listOf("ENG", "FRE", "English (Commentary)", "Subtitle"), subs.subtitleTrackInfos().map { it.label })
    }
}
