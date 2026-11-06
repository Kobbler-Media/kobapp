package dev.kobbler.app.ui.reader

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import dev.kobbler.app.UiTimeoutMs
import dev.kobbler.app.data.UserSettings
import dev.kobbler.app.platform.FileCache
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Laying a chapter out is never something the reader waits on with nothing to read. */
@OptIn(ExperimentalTestApi::class)
class ReaderLayoutAheadTest {

    private fun paragraphs(count: Int) = (1..count).map { "Paragraph $it carries enough words to wrap across a line or two of the page." }

    private val short = listOf(textChapter("First", "First chapter."), textChapter("Second", "Second chapter."), textChapter("Third", "Third chapter."))

    @Test
    fun `restyling the page keeps a page on screen`() {
        val restyled = listOf("reader_theme", "reader_font_size")
        val before = restyled.associateWith(UserSettings::getString)
        try {
            reader(listOf(TestChapter("Long", paragraphs(400))), atChapter(0, block = 200)) { _, _ ->
                assertShown("Paragraph 201 carries")
                onNodeWithContentDescription("Settings").performClick()
                waitForIdle()
                neverSpinning("choosing another theme") {
                    onNodeWithText(if (UserSettings.getString("reader_theme") == "Light") "Sepia" else "Light").performClick()
                }
                neverSpinning("a larger font") {
                    val slider = onNode(fontSizeSlider)
                    val size = slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
                    slider.performSemanticsAction(SemanticsActions.SetProgress) { it(if (size < 22f) size + 4f else size - 4f) }
                }
            }
        } finally {
            before.forEach { (key, value) -> if (value == null) UserSettings.remove(key) else UserSettings.putString(key, value) }
        }
    }

    @Test
    fun `a chapter opened from the contents shows before it is all laid out`() =
        reader(short + textChapter("Fourth", "Fourth chapter.") + TestChapter("Huge", paragraphs(6000)), atChapter(0)) { _, _ ->
            assertShown("First chapter.")
            onNodeWithContentDescription("Contents").performClick()
            onNodeWithText("Huge").performClick()
            assertShown("Paragraph 1 carries")
            val (_, firstTotal) = pageCounter()
            waitUntil(timeoutMillis = UiTimeoutMs) { pageCounter().second > firstTotal }
        }

    @Test
    fun `the chapters around the one being read are laid out before they are reached`() = reader(short + textChapter("Fourth", "Fourth chapter."), atChapter(0)) { _, id ->
        assertShown("First chapter.")
        waitUntil(timeoutMillis = UiTimeoutMs) { savedChapters(id) == setOf(0, 1, 2, 3) }
        onNodeWithContentDescription("Contents").performClick()
        neverSpinning("jumping to a chapter laid out ahead") { onNodeWithText("Third").performClick() }
        assertShown("Third chapter.")
    }

    @Test
    fun `a book reopens on its page with the chapter's page count already known`() {
        val open = mutableStateOf(true)
        reader(listOf(TestChapter("Huge", paragraphs(6000))), atChapter(0, block = 4000), open) { _, id ->
            assertShown("Paragraph 4001 carries")
            waitUntil(timeoutMillis = UiTimeoutMs) { 0 in savedChapters(id) }
            val total = pageCounter().second
            open.value = false
            waitForIdle()
            mainClock.autoAdvance = false
            open.value = true
            val deadline = System.currentTimeMillis() + UiTimeoutMs
            while (!isShown("Paragraph 4001 carries") && System.currentTimeMillis() < deadline) {
                Thread.sleep(2)
                mainClock.advanceTimeByFrame()
            }
            repeat(2) { mainClock.advanceTimeByFrame() }
            val firstTotal = pageCounter().second
            mainClock.autoAdvance = true
            assertEquals(total, firstTotal, "the reopened chapter was paginated again before it showed")
        }
    }

    private val fontSizeSlider = SemanticsMatcher("font size slider") { node ->
        node.config.getOrElseNullable(SemanticsProperties.ProgressBarRangeInfo) { null }?.range == 12f..28f
    }

    private fun ComposeUiTest.neverSpinning(what: String, action: () -> Unit) {
        waitForIdle()
        mainClock.autoAdvance = false
        action()
        repeat(60) {
            mainClock.advanceTimeByFrame()
            assertTrue(!isSpinning(), "the spinner replaced the page after $what")
        }
        mainClock.autoAdvance = true
    }

    private fun savedChapters(bookId: String): Set<Int> =
        FileCache.get(PageBreakStore.fileKey(bookId))?.let(PageBreakCodec::decode).orEmpty().map { it.chapter }.toSet()

    private fun atChapter(chapter: Int, block: Int = 0) =
        """{"progress_percent":50,"position":{"chapter":$chapter,"block":$block,"offset":0}}"""

    private fun reader(
        chapters: List<TestChapter>,
        progress: String,
        open: MutableState<Boolean> = mutableStateOf(true),
        test: ComposeUiTest.(dev.kobbler.app.ui.player.StubServer, String) -> Unit,
    ) = readerTest(progress, open, chapters, width = 800, height = 900, slowChapter = null) { server, id ->
        try {
            test(server, id)
        } finally {
            FileCache.remove(PageBreakStore.fileKey(id))
        }
    }
}
