package dev.kobbler.app.ui.reader

import dev.kobbler.app.runUiTest
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/** With the bars hidden on a TV, the page still says where the reader is and how to drive it. */
@OptIn(ExperimentalTestApi::class)
class ReaderTvStatusTest {

    private val theme = ReaderTheme.Dark

    @Test
    fun `the status line shows progress and the remote's keys, then only progress`() = runUiTest {
        val cursor = mutableStateOf(false)
        setContent {
            KobappTheme(formFactor = FormFactor.TV) {
                ReaderTvStatus(pages = { 4 to 10 }, currentChapter = 2, totalChapters = 5, cursorActive = cursor.value, theme = theme)
            }
        }
        onNodeWithText(readerProgressLabel(4 to 10, 2, 5)).assertExists()
        assertTrue(onAllNodesWithText("menu", substring = true).fetchSemanticsNodes().isNotEmpty())
        mainClock.advanceTimeBy(10_000)
        assertTrue(onAllNodesWithText("menu", substring = true).fetchSemanticsNodes().isEmpty())
        onNodeWithText(readerProgressLabel(4 to 10, 2, 5)).assertExists()
        cursor.value = true
        mainClock.advanceTimeBy(500)
        assertTrue(onAllNodesWithText("read aloud", substring = true).fetchSemanticsNodes().isNotEmpty())
    }
}
