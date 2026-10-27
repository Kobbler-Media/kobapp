package dev.kobbler.app.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import dev.kobbler.app.data.Book
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.runUiTest
import dev.kobbler.app.ui.player.audiobook.rememberAudiobookState
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Audio and video share the speakers: whichever starts, the other stops talking. */
@OptIn(ExperimentalTestApi::class)
class SharedAudioPlayerTest {

    @Test
    fun `starting audio pauses the video, and starting video pauses the audio`() = runUiTest {
        val video = FakePlayer()
        val built = mutableListOf<FakePlayer>()
        val audio = SharedAudioPlayer { FakePlayer().also { built += it } }
        setContent { ExclusiveAudio(video, audio) }

        video.play()
        waitForIdle()
        assertEquals(0, built.size, "watching video built an audio player")

        audio.get().play()
        waitUntil { !video.isPlaying.value }

        video.play()
        waitUntil { !built.single().isPlaying.value }
        assertTrue(video.isPlaying.value, "the video was paused by its own start")
    }

    @Test
    fun `leaving the audiobook screen stops the shared player without releasing it`() = runUiTest {
        val player = FakePlayer()
        var open by mutableStateOf(true)
        setContent {
            if (open) rememberAudiobookState(Book(id = "b", title = "B", author = "A", coverUrl = ""), KobblerApi(HttpClient(), "http://127.0.0.1:1", "t"), player)
        }
        waitForIdle()
        player.play()
        open = false
        waitForIdle()
        assertTrue(!player.isPlaying.value, "the audiobook kept playing after its screen closed")
        assertTrue(!player.released, "the shared player was released with the screen")
    }
}
