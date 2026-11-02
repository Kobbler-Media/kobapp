package dev.kobbler.app.ui.reader

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import dev.kobbler.app.UiTimeoutMs
import dev.kobbler.app.data.Book
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.runUiTest
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import java.util.UUID
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

    private fun reader(progress: String = """{"progress_percent":0}""", test: ComposeUiTest.() -> Unit) =
        reader(progress, mutableStateOf(true)) { _, _ -> test() }

    private fun reader(
        progress: String = """{"progress_percent":0}""",
        open: androidx.compose.runtime.MutableState<Boolean>,
        test: ComposeUiTest.(StubServer, String) -> Unit,
    ) {
        val id = "paging-${UUID.randomUUID()}"
        val server = StubServer(packRoutes(id, chapters) + ("/api/v1/admin/books/$id/reader-progress" to { _ -> progress }))
        try {
            runUiTest(width = 800, height = 900) {
                setContent {
                    KobappTheme(formFactor = FormFactor.Desktop) {
                        if (open.value) {
                            ReaderScreen(
                                book = Book(id = id, title = "Paged Book", author = "A", coverUrl = ""),
                                api = KobblerApi(HttpClient(), server.url, "t"),
                                formFactor = FormFactor.Desktop,
                                onBack = {},
                            )
                        }
                    }
                }
                waitUntil(timeoutMillis = UiTimeoutMs) { isShown("Paragraph") || isShown("Second chapter") }
                waitForIdle()
                test(server, id)
            }
        } finally {
            server.close()
        }
    }

    private fun ComposeUiTest.isShown(text: String) =
        onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.assertShown(text: String) {
        waitUntil(timeoutMillis = UiTimeoutMs) { isShown(text) }
    }

    private fun ComposeUiTest.press(key: Key) {
        onRoot().performKeyInput { pressKey(key) }
        waitForIdle()
    }
}
