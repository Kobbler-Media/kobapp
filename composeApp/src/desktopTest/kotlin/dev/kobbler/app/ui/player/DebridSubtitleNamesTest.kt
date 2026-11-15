package dev.kobbler.app.ui.player

import dev.kobbler.app.UiTimeoutMs
import dev.kobbler.app.runUiTest
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.AudioTrackDto
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.UserSettings
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.dashboard.KEY_DEFAULT_AUDIO_LANG
import dev.kobbler.kobplayer.TrackInfo
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals

/** A debrid release's tracks are named once the server has read them, mid-playback. */
@OptIn(ExperimentalTestApi::class)
class DebridSubtitleNamesTest {

    private val subtitlesFrame = "data: {\"type\":\"subtitles\",\"subtitles\":[" +
        "{\"stream_index\":3,\"codec\":\"subrip\",\"language\":\"eng\",\"is_forced\":false}," +
        "{\"stream_index\":4,\"codec\":\"subrip\",\"language\":\"fre\",\"is_forced\":true}]}\n\n"

    private val vlcTracks = listOf(TrackInfo("t1", "Track 1 - [English]"), TrackInfo("t2", "Track 2 - [English]"))

    private fun debridState() = GlobalPlayerState().apply {
        mediaType = "episode"
        mediaId = "ep-1"
        sessionId = "s1"
        isDebrid = true
    }

    private val unforcedFrame = subtitlesFrame.replace("\"is_forced\":true", "\"is_forced\":false")

    private fun audio(language: String?) = AudioTrackDto(streamIndex = 1, language = language)

    private fun play(
        state: GlobalPlayerState,
        player: FakePlayer,
        frame: String = subtitlesFrame,
        whilePlaying: androidx.compose.ui.test.ComposeUiTest.() -> Unit,
    ) {
        StubServer(mapOf("/api/v1/admin/stream/s1/status" to { _ -> frame })).use { server ->
            runUiTest {
                setContent {
                    Box(Modifier.size(960.dp, 540.dp)) {
                        KobappTheme(formFactor = FormFactor.TV) {
                            FullscreenVideoPlayer(
                                player = player, state = state, api = KobblerApi(HttpClient(), server.url, "token"),
                                formFactor = FormFactor.TV, onBack = {}, isFullscreen = true, videoSurface = { },
                            )
                        }
                    }
                }
                whilePlaying()
            }
        }
    }

    @Test
    fun `the server's reading names the player's subtitle tracks`() {
        val state = debridState()
        play(state, FakePlayer()) {
            waitUntil(timeoutMillis = UiTimeoutMs) { state.serverSubtitleTracks.isNotEmpty() }
        }
        assertEquals(listOf("eng", "fre"), state.serverSubtitleTracks.map { it.language })
        assertEquals(listOf(false, true), state.serverSubtitleTracks.map { it.isForced })
    }

    @Test
    fun `a forced track the server finds is switched on`() {
        val player = FakePlayer().apply { subtitleTracks.value = vlcTracks }
        play(debridState(), player) {
            waitUntil(timeoutMillis = UiTimeoutMs) { player.selectedSubtitleTrack.value != null }
        }
        assertEquals("t2", player.selectedSubtitleTrack.value?.id)
    }

    @Test
    fun `a forced track is switched on once the player has listed its tracks`() {
        val player = FakePlayer()
        val state = debridState()
        play(state, player) {
            waitUntil(timeoutMillis = UiTimeoutMs) { state.serverSubtitleTracks.isNotEmpty() }
            player.subtitleTracks.value = vlcTracks
            waitUntil(timeoutMillis = UiTimeoutMs) { player.selectedSubtitleTrack.value != null }
        }
        assertEquals("t2", player.selectedSubtitleTrack.value?.id)
    }

    @Test
    fun `a viewer's own subtitle pick is not overridden`() {
        val player = FakePlayer().apply {
            subtitleTracks.value = vlcTracks
            selectedSubtitleTrack.value = vlcTracks[0]
        }
        val state = debridState()
        play(state, player) {
            waitUntil(timeoutMillis = UiTimeoutMs) { state.serverSubtitleTracks.isNotEmpty() }
            waitForIdle()
        }
        assertEquals("t1", player.selectedSubtitleTrack.value?.id)
    }

    private fun unforced(audioLanguage: String?, preferred: String?): String? {
        UserSettings.bind { "debrid-subtitle-test" }
        if (preferred != null) UserSettings.putString(KEY_DEFAULT_AUDIO_LANG, preferred)
        try {
            val player = FakePlayer().apply { subtitleTracks.value = vlcTracks }
            val state = debridState().apply {
                serverAudioTracks = listOf(audio(audioLanguage))
                selectedServerAudioIndex = 1
            }
            play(state, player, unforcedFrame) {
                waitUntil(timeoutMillis = UiTimeoutMs) { state.serverSubtitleTracks.isNotEmpty() }
                waitForIdle()
            }
            return player.selectedSubtitleTrack.value?.id
        } finally {
            UserSettings.remove(KEY_DEFAULT_AUDIO_LANG)
            UserSettings.bind { null }
        }
    }

    @Test
    fun `audio in another language switches on subtitles in the viewer's`() {
        assertEquals("t1", unforced(audioLanguage = "rus", preferred = "eng"))
    }

    @Test
    fun `audio the viewer understands leaves unforced subtitles off`() {
        assertEquals(null, unforced(audioLanguage = "eng", preferred = "eng"))
    }

    @Test
    fun `untagged audio is not taken for a foreign language`() {
        assertEquals(null, unforced(audioLanguage = null, preferred = "eng"))
    }
}
