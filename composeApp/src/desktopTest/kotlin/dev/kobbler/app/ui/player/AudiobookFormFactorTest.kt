package dev.kobbler.app.ui.player

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.sp
import dev.kobbler.app.data.AudioFileDto
import dev.kobbler.app.data.Book
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.UnifiedChapterDto
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.fontSize
import dev.kobbler.app.ui.player.audiobook.AudiobookPlayerState
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class AudiobookFormFactorTest {

    private val book = Book(id = "b1", title = "Project Hail Mary", author = "Andy Weir", coverUrl = "")

    @Test
    fun `the audiobook player keeps the TV type scale`() = runComposeUiTest {
        val state = AudiobookPlayerState(
            book,
            KobblerApi(HttpClient(), "http://127.0.0.1:1", "token"),
            FakePlayer(),
            CoroutineScope(Dispatchers.Unconfined),
        ).apply {
            audioFiles = listOf(AudioFileDto(id = "f1", fileIndex = 0, format = "m4b", fileName = "f1.m4b", durationSeconds = 600.0))
            chapters = listOf(
                UnifiedChapterDto(
                    id = "c1", chapterIndex = 0, title = "Chapter One", startSeconds = 0.0, endSeconds = 600.0,
                    fileId = "f1", globalStartSeconds = 0.0, globalEndSeconds = 600.0,
                ),
            )
        }
        setContent {
            KobappTheme(formFactor = FormFactor.TV) {
                AudiobookPlayerContent(book = book, state = state, onBack = {}, onOpenReader = null)
            }
        }

        assertEquals(28.sp, fontSize(book.title))
    }
}
