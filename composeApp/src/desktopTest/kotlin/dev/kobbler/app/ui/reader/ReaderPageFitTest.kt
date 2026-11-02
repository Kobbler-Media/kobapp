package dev.kobbler.app.ui.reader

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.DpRect
import dev.kobbler.app.UiTimeoutMs
import dev.kobbler.app.data.Book
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.UserSettings
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.runUiTest
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Picture pages fit the screen — a physical page per screen, two side by side where they fit. */
@OptIn(ExperimentalTestApi::class)
class ReaderPageFitTest {

    private val pageFitKey = "reader_page_fit"
    private val screenW = 1920
    private val screenH = 1080

    @Test
    fun `a picture book turns spread by spread, cover alone, and the setting toggles from the remote`() =
        pictureBook(rtl = false) {
            assertShown("Page 1")
            assertFits("Page 1")
            assertHidden("Page 2")

            press(Key.DirectionRight)
            awaitShown("Page 2", "Page 3")
            assertFits("Page 2")
            assertFits("Page 3")
            assertTrue(bounds("Page 2").right <= bounds("Page 3").left + 1, "LTR spread put page 3 before page 2")

            press(Key.DirectionRight)
            awaitShown("Page 4", "Page 5")
            press(Key.DirectionLeft)
            awaitShown("Page 2", "Page 3")

            chooseInSettings("Fit page")
            awaitSaved(ReaderPageFit.Page)
            awaitShown("Page 3")
            assertHidden("Page 2")
            assertFits("Page 3")

            chooseInSettings("Fit width")
            awaitShown("Page 3")
            assertTrue(bounds("Page 3").height > screenH, "fit width left page 3 at ${bounds("Page 3")}, not taller than the screen")
        }

    @Test
    fun `a right-to-left book lays its spreads out right to left`() = pictureBook(rtl = true) {
        press(Key.DirectionRight)
        awaitShown("Page 2", "Page 3")
        assertTrue(bounds("Page 3").right <= bounds("Page 2").left + 1, "RTL spread put page 2 on the left")
    }

    private fun ComposeUiTest.awaitSaved(fit: ReaderPageFit) =
        waitUntil(timeoutMillis = UiTimeoutMs) { UserSettings.getString(pageFitKey) == fit.name }

    private fun pictureBook(rtl: Boolean, test: ComposeUiTest.() -> Unit) {
        val id = "fit-${UUID.randomUUID()}"
        val pictures = (1..7).map { pictureChapter("Page $it") }
        UserSettings.remove(pageFitKey)
        val server = StubServer(
            packRoutes(id, pictures, fixedLayout = true, rtl = rtl) +
                ("/api/v1/admin/books/$id/reader-progress" to { _ -> """{"progress_percent":0}""" }),
        )
        try {
            runUiTest(width = screenW, height = screenH) {
                setContent {
                    KobappTheme(formFactor = FormFactor.TV) {
                        ReaderScreen(
                            book = Book(id = id, title = "Picture Book", author = "A", coverUrl = ""),
                            api = KobblerApi(HttpClient(), server.url, "t"),
                            formFactor = FormFactor.TV,
                            onBack = {},
                        )
                    }
                }
                awaitShown("Page 1")
                test()
            }
        } finally {
            server.close()
            UserSettings.remove(pageFitKey)
        }
    }

    private fun ComposeUiTest.press(key: Key) {
        onRoot().performKeyInput { pressKey(key) }
        waitForIdle()
    }

    private fun ComposeUiTest.chooseInSettings(option: String) {
        press(Key.DirectionUp)
        onNodeWithContentDescription("Settings").requestFocus()
        press(Key.Enter)
        onNodeWithText(option).requestFocus()
        press(Key.Enter)
        press(Key.Back)
    }

    private fun ComposeUiTest.isShown(page: String) =
        onAllNodesWithContentDescription(page).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.awaitShown(vararg pages: String) {
        waitUntil(timeoutMillis = UiTimeoutMs) { pages.all { isShown(it) } }
        waitForIdle()
    }

    private fun ComposeUiTest.assertShown(page: String) = assertTrue(isShown(page), "$page is not on screen")

    private fun ComposeUiTest.assertHidden(page: String) = assertTrue(!isShown(page), "$page should not be on screen")

    private fun ComposeUiTest.bounds(page: String): Rect {
        val r: DpRect = onNodeWithContentDescription(page).getUnclippedBoundsInRoot()
        return Rect(r.left.value, r.top.value, r.right.value, r.bottom.value)
    }

    private fun ComposeUiTest.assertFits(page: String) {
        val b = bounds(page)
        assertTrue(b.height <= screenH + 1 && b.top >= -1 && b.bottom <= screenH + 1, "$page is $b, not inside the ${screenW}x$screenH screen")
        assertTrue(b.height >= screenH * 0.9f, "$page was shrunk to ${b.height}, not fitted to the ${screenH} screen height")
    }
}
