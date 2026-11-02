package dev.kobbler.app.ui.player

import dev.kobbler.app.UiTimeoutMs
import dev.kobbler.app.runUiTest
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals

/** A debrid release's tracks are named once the server has read them, mid-playback. */
@OptIn(ExperimentalTestApi::class)
class DebridSubtitleNamesTest {

    private val subtitlesFrame = "data: {\"type\":\"subtitles\",\"subtitles\":[" +
        "{\"stream_index\":3,\"codec\":\"subrip\",\"language\":\"eng\",\"is_forced\":false}," +
        "{\"stream_index\":4,\"codec\":\"subrip\",\"language\":\"fre\",\"is_forced\":true}]}\n\n"

    @Test
    fun `the server's reading names the player's subtitle tracks`() {
        val state = GlobalPlayerState().apply {
            mediaType = "episode"
            mediaId = "ep-1"
            sessionId = "s1"
            isDebrid = true
        }
        StubServer(mapOf("/api/v1/admin/stream/s1/status" to { _ -> subtitlesFrame })).use { server ->
            runUiTest {
                setContent {
                    Box(Modifier.size(960.dp, 540.dp)) {
                        KobappTheme(formFactor = FormFactor.TV) {
                            FullscreenVideoPlayer(
                                player = FakePlayer(), state = state, api = KobblerApi(HttpClient(), server.url, "token"),
                                formFactor = FormFactor.TV, onBack = {}, isFullscreen = true, videoSurface = { },
                            )
                        }
                    }
                }
                waitUntil(timeoutMillis = UiTimeoutMs) { state.serverSubtitleTracks.isNotEmpty() }
            }
        }
        assertEquals(listOf("eng", "fre"), state.serverSubtitleTracks.map { it.language })
        assertEquals(listOf(false, true), state.serverSubtitleTracks.map { it.isForced })
    }
}
