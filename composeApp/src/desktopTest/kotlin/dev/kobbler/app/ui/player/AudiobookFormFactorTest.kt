package dev.kobbler.app.ui.player

import dev.kobbler.app.runUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AudiobookFormFactorTest {

    private val book = Book(id = "b1", title = "Project Hail Mary", author = "Andy Weir", coverUrl = "")

    private fun state() = AudiobookPlayerState(
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

    @Test
    fun `the audiobook player keeps the TV type scale`() = runUiTest {
        val state = state()
        setContent {
            KobappTheme(formFactor = FormFactor.TV) {
                AudiobookPlayerContent(book = book, state = state, onBack = {}, onOpenReader = null)
            }
        }

        assertEquals(28.sp, fontSize(book.title))
    }

    /** A remote cannot scroll a column it has no focus target in, so the transport has to fit. */
    @Test
    fun `on a TV screen the cover leaves room for the transport`() = runUiTest(width = 960, height = 540) {
        val state = state()
        setContent {
            KobappTheme(formFactor = FormFactor.TV) {
                AudiobookPlayerContent(book = book, state = state, onBack = {}, onOpenReader = null)
            }
        }
        waitForIdle()
        val play = onNodeWithContentDescription("Play").getUnclippedBoundsInRoot()
        assertTrue(play.bottom <= 540.dp, "the play button ends at ${play.bottom} of 540dp")
    }

    private fun backShown(formFactor: FormFactor): Boolean {
        var shown = false
        runUiTest(width = 960, height = 540) {
            val state = state()
            setContent {
                KobappTheme(formFactor = formFactor) {
                    AudiobookPlayerContent(book = book, state = state, onBack = {}, onOpenReader = null)
                }
            }
            waitForIdle()
            shown = onAllNodesWithContentDescription("Back").fetchSemanticsNodes().isNotEmpty()
        }
        return shown
    }

    @Test
    fun `a TV leaves going back to the remote`() = assertFalse(backShown(FormFactor.TV))

    @Test
    fun `a touchscreen keeps its back button`() = assertTrue(backShown(FormFactor.Phone))

    @Test
    fun `the clocks share one row`() = runUiTest(width = 960, height = 540) {
        val state = state()
        setContent {
            KobappTheme(formFactor = FormFactor.TV) {
                AudiobookPlayerContent(book = book, state = state, onBack = {}, onOpenReader = null)
            }
        }
        state.currentMs = 65_000
        waitForIdle()
        val rows = listOf("1:05", "8:55 left in chapter", "10:00").map { label ->
            onAllNodesWithText(label)[0].getUnclippedBoundsInRoot().let { (it.top + it.bottom) / 2 }
        }
        assertTrue(rows.max() - rows.min() < 1.dp, "the clocks sit on different rows: $rows")
        assertTrue(onAllNodesWithText("-8:55").fetchSemanticsNodes().isEmpty(), "a second countdown is still ticking")
    }
}
