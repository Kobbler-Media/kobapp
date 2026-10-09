package dev.kobbler.app.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.PlaybackSubtitleTrackDto
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.kobfocus.FocusScopeHost
import dev.kobbler.kobfocus.FocusScopeStore
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test

/** Shrinking the player is a change of size, not of what is being watched. */
@OptIn(ExperimentalTestApi::class)
class MiniPlayerSubtitlesTest {

    private val vtt = """
        WEBVTT

        00:00:01.000 --> 00:00:03.000
        Hello there
    """.trimIndent()

    private fun showsCue(mini: Boolean) = StubServer(mapOf("/subs/en.vtt" to { _ -> vtt })).use { server ->
        runComposeUiTest {
            val player = FakePlayer().apply {
                durationMs.value = 60_000
                currentMs.value = 1_500
                isPlaying.value = true
            }
            val state = GlobalPlayerState().apply {
                mediaType = "movie"
                mediaId = "mov-1"
                sessionId = "sess-1"
                title = "Film"
                serverSubtitleTracks = listOf(
                    PlaybackSubtitleTrackDto(trackId = "en", url = "${server.url}/subs/en.vtt", format = "vtt"),
                )
                selectedServerSubtitleTrackId = "en"
                isMiniPlayer = mini
                isFullscreen = !mini
            }
            setContent {
                Box(Modifier.size(960.dp, 540.dp)) {
                    FocusScopeHost(id = "test", store = FocusScopeStore()) {
                        KobappTheme(formFactor = FormFactor.Phone) {
                            PersistentVideoSurface(
                                player = player,
                                state = state,
                                api = KobblerApi(HttpClient(), server.url, "token"),
                                formFactor = FormFactor.Phone,
                                onToggleFullscreen = null,
                                isFullscreen = !mini,
                                scope = CoroutineScope(Dispatchers.Unconfined),
                                videoSurface = { },
                            )
                        }
                    }
                }
            }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("Hello there").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("Hello there").assertExists()
        }
    }

    @Test
    fun `the mini player keeps drawing subtitles`() = showsCue(mini = true)

    @Test
    fun `the fullscreen player draws subtitles`() = showsCue(mini = false)
}
