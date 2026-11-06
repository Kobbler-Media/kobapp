package dev.kobbler.app.ui.reader

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performTouchInput
import dev.kobbler.app.UiTimeoutMs
import dev.kobbler.app.ui.player.StubServer
import kotlin.test.Test
import kotlin.test.assertTrue

/** A book reads as pages: turned one at a time, across chapters, resumed where it was left. */
@OptIn(ExperimentalTestApi::class)
class ReaderPagingTest {

    private val paragraphs = (1..150).map { "Paragraph $it carries enough words to wrap across a line or two of the page." }
    private val chapters = listOf(
        TestChapter("Long", paragraphs),
        textChapter("Next", "Second chapter begins."),
    )

    @Test
    fun `pages turn through a chapter and on into the next`() = reader {
        assertShown("Paragraph 1 carries")
        assertTrue(!isShown("Paragraph 150 carries"), "a 150-paragraph chapter fit on one page")
        var turns = 0
        while (!isShown("Second chapter begins") && turns < 100) {
            press(Key.DirectionRight)
            turns++
        }
        assertShown("Second chapter begins")
        assertTrue(turns > 3, "reached the next chapter in $turns turns: the chapter was not paged")
        assertShown("Ch 2/2")

        press(Key.DirectionLeft)
        assertShown("Paragraph 150 carries")
        assertShown("Ch 1/2")
    }

    @Test
    fun `paging back across a chapter lands on its last page`() =
        reader(progress = atChapter(1), slowChapter = 0) {
            assertShown("Second chapter begins")
            repeat(30) { Thread.sleep(100); waitForIdle() }
            press(Key.DirectionLeft)
            assertShown("Ch 1/2")
            waitForIdle()
            assertShown("Paragraph 150 carries")
            assertTrue(!isShown("Paragraph 1 carries"), "paged back to the start of the chapter, not its end")
            val (last, total) = pageCounter()
            assertTrue(last == total, "landed on page $last of $total")
            press(Key.DirectionLeft)
            waitUntil(timeoutMillis = UiTimeoutMs) { pageCounter().first != last }
            waitForIdle()
            assertTrue(pageCounter() == (total - 1 to total), "the next turn back went to ${pageCounter()}, not ${total - 1}/$total")
        }

    @Test
    fun `crossing a chapter keeps the page on screen`() =
        reader(progress = atChapter(0, block = 149)) {
            assertShown("Paragraph 150 carries")
            for ((key, arrival) in listOf(Key.DirectionRight to "Second chapter begins", Key.DirectionLeft to "Paragraph 150 carries")) {
                waitForIdle()
                mainClock.autoAdvance = false
                onRoot().performKeyInput { pressKey(key) }
                repeat(90) {
                    mainClock.advanceTimeByFrame()
                    assertTrue(!isSpinning(), "the spinner replaced the page after pressing $key")
                    assertTrue(!isShown("Paragraph 1 carries"), "pressing $key passed through the start of the long chapter")
                }
                mainClock.autoAdvance = true
                assertShown(arrival)
            }
        }

    @Test
    fun `the empty half of a spread still turns the page`() =
        reader(
            progress = atChapter(1),
            chapters = listOf(TestChapter("Long", paragraphs), textChapter("Short", "Short chapter."), textChapter("Third", "Third chapter begins.")),
            width = 1400,
            height = 700,
        ) {
            assertShown("Short chapter.")
            onRoot().performTouchInput { click(Offset(width * 0.9f, height / 2f)) }
            assertShown("Third chapter begins")
        }

    @Test
    fun `a saved position opens on the page that holds it`() =
        reader(progress = """{"progress_percent":40,"position":{"chapter":0,"block":45,"offset":0}}""") {
            assertShown("Paragraph 46 carries")
            assertTrue(!isShown("Paragraph 1 carries"), "resumed at the chapter start, not the saved position")
        }

    @Test
    fun `leaving saves the position at the top of the page`() {
        val open = mutableStateOf(true)
        reader(open = open) { server, id ->
            press(Key.DirectionRight)
            press(Key.DirectionRight)
            open.value = false
            // The resume GET is recorded under the same path with an empty body.
            waitUntil(timeoutMillis = UiTimeoutMs) {
                server.requestBodies["/api/v1/admin/books/$id/reader-progress"].orEmpty().contains("position")
            }
            val body = server.requestBodies.getValue("/api/v1/admin/books/$id/reader-progress")
            assertTrue(Regex(""""position":\{"chapter":0,"block":([1-9]\d*)""").containsMatchIn(body), body)
        }
    }

    private fun atChapter(chapter: Int, block: Int = 0) =
        """{"progress_percent":50,"position":{"chapter":$chapter,"block":$block,"offset":0}}"""

    private fun reader(
        progress: String = """{"progress_percent":0}""",
        chapters: List<TestChapter> = this.chapters,
        width: Int = 800,
        height: Int = 900,
        slowChapter: Int? = null,
        test: ComposeUiTest.() -> Unit,
    ) = reader(progress, mutableStateOf(true), chapters, width, height, slowChapter) { _, _ -> test() }

    private fun reader(
        progress: String = """{"progress_percent":0}""",
        open: androidx.compose.runtime.MutableState<Boolean>,
        chapters: List<TestChapter> = this.chapters,
        width: Int = 800,
        height: Int = 900,
        slowChapter: Int? = null,
        test: ComposeUiTest.(StubServer, String) -> Unit,
    ) = readerTest(progress, open, chapters, width, height, slowChapter, test)
}
