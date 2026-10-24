package dev.kobbler.app.ui.player

import dev.kobbler.app.runUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import dev.kobbler.app.data.AudioFileDto
import dev.kobbler.app.data.Book
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.ui.player.audiobook.rememberAudiobookState
import dev.kobbler.kobplayer.PlayerState
import io.ktor.client.HttpClient
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class AudiobookProgressTest {

    private val book = Book(id = "bk-1", title = "Project Hail Mary", author = "Andy Weir", coverUrl = "")
    private val api = KobblerApi(HttpClient(), "http://127.0.0.1:1", "token")

    @Test
    fun `the screen follows the player`() = runUiTest(width = 420, height = 900) {
        val player = FakePlayer()
        setContent {
            val state = rememberAudiobookState(book, api, player)
            state.audioFiles = listOf(
                AudioFileDto(id = "f1", fileIndex = 0, format = "m4b", fileName = "f1.m4b", durationSeconds = 3_600.0),
            )
            AudiobookPlayerContent(book, state, onBack = {}, onOpenReader = null)
        }
        waitForIdle()

        player.currentMs.value = 65_000
        player.state.value = PlayerState.PLAYING
        waitForIdle()

        onNodeWithText("1:05").assertExists()
        onNodeWithContentDescription("Pause").assertExists()
    }
}
