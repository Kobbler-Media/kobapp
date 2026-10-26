package dev.kobbler.app.ui.player

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import dev.kobbler.app.data.AudioFileDto
import dev.kobbler.app.data.Book
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.UnifiedChapterDto
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.runUiTest
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.player.audiobook.AudiobookPlayerState
import dev.kobbler.kobplayer.ui.formatTime
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class AudiobookScrubTest {

    private val book = Book(id = "b1", title = "Project Hail Mary", author = "Andy Weir", coverUrl = "")

    private fun chapter(index: Int, start: Double, end: Double) = UnifiedChapterDto(
        id = "c$index", chapterIndex = index, title = "Chapter ${index + 1}", startSeconds = start, endSeconds = end,
        fileId = "f1", globalStartSeconds = start, globalEndSeconds = end,
    )

    @Test
    fun `dragging the seek bar moves the time labels before the finger lifts`() = runUiTest {
        val state = AudiobookPlayerState(
            book,
            KobblerApi(HttpClient(), "http://127.0.0.1:1", "token"),
            FakePlayer(),
            CoroutineScope(Dispatchers.Unconfined),
        ).apply {
            audioFiles = listOf(AudioFileDto(id = "f1", fileIndex = 0, format = "m4b", fileName = "f1.m4b", durationSeconds = 600.0))
            chapters = listOf(chapter(0, 0.0, 300.0), chapter(1, 300.0, 600.0))
        }
        setContent {
            KobappTheme(formFactor = FormFactor.Phone) {
                AudiobookPlayerContent(book = book, state = state, onBack = {}, onOpenReader = null)
            }
        }

        onNodeWithContentDescription("Seek").performTouchInput {
            down(centerLeft)
            moveTo(percentOffset(0.75f, 0.5f))
        }
        waitForIdle()

        onNodeWithText(formatTime(450_000)).assertExists()
        onNodeWithText("${formatTime(150_000)} left in chapter").assertExists()
    }
}
