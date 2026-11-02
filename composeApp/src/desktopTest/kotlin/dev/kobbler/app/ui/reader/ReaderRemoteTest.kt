package dev.kobbler.app.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** An ebook on a TV is read with nothing but a D-pad, OK and BACK. */
@OptIn(ExperimentalTestApi::class)
class ReaderRemoteTest {

    private val chapterCount = 40
    private val chapters = (1..chapterCount).map { textChapter("Chapter $it", "Words of chapter $it.") }
    private val topBarButtons = listOf("Read Aloud", "Search", "Bookmark", "Contents", "Settings")

    private class Rig {
        var backs = 0
    }

    private fun tvReader(progressPercent: Double = 0.0, test: ComposeUiTest.(Rig) -> Unit) {
        val id = "remote-${UUID.randomUUID()}"
        val server = StubServer(
            packRoutes(id, chapters) +
                ("/api/v1/admin/books/$id/reader-progress" to { _ -> """{"progress_percent":$progressPercent}""" }),
        )
        try {
            runUiTest(width = 1920, height = 1080) {
                val rig = Rig()
                setContent {
                    KobappTheme(formFactor = FormFactor.TV) {
                        AndroidDpad {
                            ReaderScreen(
                                book = Book(id = id, title = "Remote Book", author = "A", coverUrl = ""),
                                api = KobblerApi(HttpClient(), server.url, "t"),
                                formFactor = FormFactor.TV,
                                onBack = { rig.backs++ },
                            )
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

    private fun ComposeUiTest.press(key: Key) = press(listOf(key))

    private fun ComposeUiTest.press(keys: List<Key>) {
        for (k in keys) {
            onRoot().performKeyInput { pressKey(k) }
            waitForIdle()
        }
    }

    private fun ComposeUiTest.focusedTexts(): List<String> =
        onAllNodes(isFocused()).fetchSemanticsNodes().flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        }

    private fun ComposeUiTest.assertFocusedText(text: String) {
        assertTrue(
            onAllNodes(isFocused() and hasText(text)).fetchSemanticsNodes().isNotEmpty(),
            "expected \"$text\" focused, focus is on ${focusedTexts()}",
        )
    }

    private fun ComposeUiTest.assertTopBarUnfocused() {
        for (button in topBarButtons) {
            assertTrue(
                onAllNodes(isFocused() and hasContentDescription(button)).fetchSemanticsNodes().isEmpty(),
                "focus escaped the panel to the top bar's $button",
            )
        }
    }

    private fun ComposeUiTest.openFromTopBar(button: String) {
        press(Key.DirectionUp)
        onNodeWithContentDescription(button).requestFocus()
        press(Key.Enter)
    }

    private fun ComposeUiTest.isShown(marker: String) =
        onAllNodesWithText(marker).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.isShownPart(text: String) =
        onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private val settingsMarker = "Reader Settings"
    private val tocMarker = "Table of Contents"

    @Test
    fun `back closes the panel on top, not the book`() {
        for ((button, marker) in listOf("Settings" to settingsMarker, "Contents" to tocMarker)) {
            tvReader { rig ->
                openFromTopBar(button)
                assertTrue(isShown(marker), "$button did not open")
                press(Key.Back)
                assertTrue(!isShown(marker), "BACK left $button open")
                assertEquals(0, rig.backs, "BACK in $button closed the book")
            }
        }
    }

    @Test
    fun `a panel holds focus until it is closed`() {
        for ((button, marker) in listOf("Settings" to settingsMarker, "Contents" to tocMarker)) {
            tvReader {
                openFromTopBar(button)
                assertTrue(onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty(), "$button opened with nothing focused")
                assertTopBarUnfocused()
                press(listOf(Key.DirectionUp, Key.DirectionUp, Key.DirectionUp, Key.DirectionLeft, Key.DirectionUp))
                assertTrue(isShown(marker), "$button closed itself")
                assertTrue(onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty(), "focus fell out of $button")
                assertTopBarUnfocused()
            }
        }
    }

    @Test
    fun `down opens the chapters on the one being read, and the remote walks and picks them`() {
        val reading = chapterForProgressPercent(75.0, List(chapterCount) { 100 })
        tvReader(progressPercent = 75.0) {
            assertTrue(isShownPart("Ch ${reading + 1}/$chapterCount"), "reader did not resume at chapter ${reading + 1}")
            press(Key.DirectionDown)
            assertTrue(isShown(tocMarker), "DOWN did not open the chapters")
            assertFocusedText("Chapter ${reading + 1}")
            press(listOf(Key.DirectionDown, Key.DirectionDown))
            assertFocusedText("Chapter ${reading + 3}")
            press(Key.Enter)
            assertTrue(!isShown(tocMarker), "picking a chapter left the list open")
            waitUntil(timeoutMillis = UiTimeoutMs) { isShownPart("Ch ${reading + 3}/$chapterCount") }
        }
    }

    @Test
    fun `the TV menu has no on-screen back, and up still lands in it`() = tvReader {
        press(Key.DirectionUp)
        assertTrue(onAllNodesWithContentDescription("Back").fetchSemanticsNodes().isEmpty(), "TV shows an on-screen back button")
        assertTrue(
            onAllNodes(isFocused() and SemanticsMatcher("top bar button") { node ->
                topBarButtons.any { hasContentDescription(it).matches(node) }
            }).fetchSemanticsNodes().isNotEmpty(),
            "UP did not focus the menu",
        )
    }

    @Test
    fun `the TV bottom bar's position opens the chapters, not a slider`() = runUiTest {
        var opened = 0
        setContent {
            ReaderBottomBar(
                pages = { 0 to 1 },
                currentChapter = 0,
                totalChapters = 20,
                onSliderChange = {},
                theme = ReaderTheme.Dark,
                entryFocus = remember { FocusRequester() },
                onChapters = { opened++ },
            )
        }
        onNodeWithText("Ch 1/20", substring = true).requestFocus()
        press(Key.Enter)
        assertEquals(1, opened)
        assertTrue(onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).fetchSemanticsNodes().isEmpty())
    }

    /** Desktop leaves an unconsumed arrow alone; Android turns it into a focus move. */
    @Composable
    private fun AndroidDpad(content: @Composable () -> Unit) {
        val focus = LocalFocusManager.current
        Box(
            Modifier.onKeyEvent { event ->
                val direction = when (event.key) {
                    Key.DirectionUp -> FocusDirection.Up
                    Key.DirectionDown -> FocusDirection.Down
                    Key.DirectionLeft -> FocusDirection.Left
                    Key.DirectionRight -> FocusDirection.Right
                    else -> return@onKeyEvent false
                }
                event.type == KeyEventType.KeyDown && focus.moveFocus(direction)
            },
        ) { content() }
    }
}
