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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpRect
import dev.kobbler.app.UiTimeoutMs
import dev.kobbler.app.data.Book
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.UserSettings
import dev.kobbler.app.platform.FileCache
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.runUiTest
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.player.StubServer
import dev.kobbler.app.ui.reader.epub.XhtmlBlock
import io.ktor.client.HttpClient
import java.io.File
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
    fun `a reflowed image taller than the page shrinks to fit it, unless fitting by width`() {
        val tall = XhtmlBlock.Image("tall.png", "Tall", TextAlign.Center, width = 400, height = 1600)
        val kobo = XhtmlBlock.FixedPage(XhtmlBlock.Image("art.jpg", "Art", width = 600, height = 914), emptyList())
        val pageHeight = 600
        for ((imageMaxHeight, fits) in listOf(pageHeight to true, 0 to false)) {
            runUiTest(width = 800, height = pageHeight) {
                val heights = mutableMapOf<Int, Int>()
                setContent {
                    EpubContent(
                        blocks = listOf(tall, kobo),
                        theme = ReaderTheme.Dark,
                        pageHeight = pageHeight,
                        imageMaxHeight = imageMaxHeight,
                        onBlockPositioned = { idx, _, h -> heights[idx] = h },
                    )
                }
                waitForIdle()
                assertEquals(2, heights.size)
                for ((idx, h) in heights) {
                    assertEquals(fits, h <= pageHeight, "block $idx is $h tall on a $pageHeight page (cap $imageMaxHeight)")
                }
            }
        }
    }

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
        val pages = 7
        val ids = (1..pages).map { "p$it" }
        val files = ids.flatMapIndexed { i, id ->
            listOf(
                "$id.xhtml" to
                    """<html><body><div><img src="$id.png" alt="Page ${i + 1}"/></div></body></html>""".encodeToByteArray(),
                "$id.png" to TestImages.png(600, 900),
            )
        }
        val bytes = epubZip(
            opf(
                ids,
                metadata = """<meta property="rendition:layout">pre-paginated</meta>""",
                spineAttrs = if (rtl) """page-progression-direction="rtl"""" else "",
            ),
            files,
        )
        val id = "fit-${UUID.randomUUID()}"
        val key = "epub_$id"
        FileCache.put(key, bytes)
        UserSettings.remove(pageFitKey)
        val server = StubServer(mapOf("/api/v1/admin/books/$id/reader-progress" to { _ -> """{"progress_percent":0}""" }))
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
            FileCache.pathFor(key)?.let { File(it).delete() }
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
