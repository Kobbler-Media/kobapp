package dev.kobbler.app.ui.reader

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import dev.kobbler.app.UiTimeoutMs
import dev.kobbler.app.data.Book
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.runUiTest
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.player.FakePlayer
import dev.kobbler.app.ui.player.LocalSharedAudioPlayer
import dev.kobbler.app.ui.player.SharedAudioPlayer
import dev.kobbler.app.ui.player.StubServer
import dev.kobbler.kobplayer.PlayerConfig
import io.ktor.client.HttpClient
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Listening along plays through the app's one audio player, built the first time anything
 * needs audio and kept after: a reader that made its own crashed on Android, and one made
 * per press paid for a whole libVLC each time.
 */
@OptIn(ExperimentalTestApi::class)
class ReaderListenAlongTest {

    private class Rig {
        val made = mutableListOf<Pair<PlayerConfig, FakePlayer>>()
        val open = mutableStateOf(true)
        val audio = SharedAudioPlayer { config -> FakePlayer().also { made += config to it } }
    }

    private fun tvReader(test: ComposeUiTest.(Rig) -> Unit) {
        val id = "listen-${UUID.randomUUID()}"
        val server = StubServer(
            packRoutes(id, listOf(textChapter("Chapter 1", "Words."))) + mapOf(
                "/api/v1/admin/books/$id/reader-progress" to { _ -> """{"progress_percent":0}""" },
                "/api/v1/admin/books/$id/read-along-data" to { _ -> "{}" },
                "/api/v1/admin/books/$id/audio-files" to { _ ->
                    """[{"id":"f1","file_index":0,"format":"mp3","file_name":"one.mp3"}]"""
                },
                "/api/v1/admin/playback/tokens" to { _ -> """{"token":"pt"}""" },
            ),
        )
        try {
            runUiTest(width = 960, height = 540) {
                val rig = Rig()
                setContent {
                    KobappTheme(formFactor = FormFactor.TV) {
                        CompositionLocalProvider(LocalSharedAudioPlayer provides rig.audio) {
                            if (rig.open.value) {
                                ReaderScreen(
                                    book = Book(id = id, title = "Both Ways", author = "A", coverUrl = "", hasAudiobook = true, formats = listOf("epub")),
                                    api = KobblerApi(HttpClient(), server.url, "t"),
                                    formFactor = FormFactor.TV,
                                    onBack = {},
                                )
                            }
                        }
                    }
                }
                waitUntil(timeoutMillis = UiTimeoutMs) {
                    onAllNodesWithText("Ch ", substring = true).fetchSemanticsNodes().isNotEmpty()
                }
                waitForIdle()
                test(rig)
            }
        } finally {
            server.close()
        }
    }

    private fun ComposeUiTest.pressListenAlong() {
        onAllNodes(isRoot()).onFirst().performKeyInput { pressKey(Key.DirectionUp) }
        waitForIdle()
        onNodeWithContentDescription("Listen Along").requestFocus()
        onAllNodes(isRoot()).onFirst().performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
    }

    private fun ComposeUiTest.startListening(rig: Rig): FakePlayer {
        pressListenAlong()
        waitUntil(timeoutMillis = UiTimeoutMs) { rig.made.singleOrNull()?.second?.calls?.contains("play") == true }
        val (config, player) = rig.made.single()
        assertTrue(config.audioOnly, "listen-along asked for a video player")
        assertTrue(player.loadedUrl.orEmpty().contains("f1"), "listen-along loaded ${player.loadedUrl}, not the audiobook")
        return player
    }

    @Test
    fun `no audio player is built until something plays audio`() = tvReader { rig ->
        assertEquals(0, rig.made.size, "opening the reader built an audio player")
    }

    @Test
    fun `pressing the headphones again stops the audio, and the next press reuses the player`() = tvReader { rig ->
        val player = startListening(rig)
        pressListenAlong()
        waitUntil(timeoutMillis = UiTimeoutMs) { !player.isPlaying.value }
        assertTrue(!player.released, "the shared player was released, not stopped")
        pressListenAlong()
        waitUntil(timeoutMillis = UiTimeoutMs) { player.calls.count { it == "play" } >= 2 }
        assertEquals(1, rig.made.size, "a second audio player was built")
    }

    @Test
    fun `leaving the reader stops listening along`() = tvReader { rig ->
        val player = startListening(rig)
        rig.open.value = false
        waitForIdle()
        assertTrue(!player.isPlaying.value, "listen-along kept playing after the reader closed")
        assertTrue(!player.released, "the shared player went with the reader")
    }
}
