package dev.kobbler.app.ui.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import dev.kobbler.app.UiTimeoutMs
import dev.kobbler.app.data.Book
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.runUiTest
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.player.StubServer
import dev.kobbler.app.ui.reader.pack.BlockFloat
import dev.kobbler.app.ui.reader.pack.FloatSide
import dev.kobbler.app.ui.reader.pack.ImageRef
import io.ktor.client.HttpClient
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertTrue

/** A page that is only a picture is shown as large as the page allows, and opens to be zoomed. */
@OptIn(ExperimentalTestApi::class)
class ReaderImageTest {

    private val width = 800
    private val illustration = TestChapter("Drawn", listOf("A paragraph before the drawing."), ImageRef("OEBPS/map.png", "Map", 1000, 500))

    @Test
    fun `a picture alone on a page fills the page width`() = reader(illustration) {
        turnToPicture()
        awaitImage("Map")
        val shown = onAllNodesWithContentDescription("Map").onFirst().getUnclippedBoundsInRoot()
        assertTrue(shown.width >= (width - 1).dp, "the picture filled ${shown.width} of a ${width}px page")
    }

    @Test
    fun `a small picture alone on a page keeps its own size`() =
        reader(TestChapter("Logo", listOf("A paragraph before the drawing."), ImageRef("OEBPS/logo.png", "Logo", 50, 50))) {
            turnToPicture("Logo")
            val shown = onAllNodesWithContentDescription("Logo").onFirst().getUnclippedBoundsInRoot()
            assertTrue(shown.width <= 51.dp, "a 50px logo was blown up to ${shown.width}")
        }

    @Test
    fun `tapping the middle of a picture opens it, the edges still turn`() =
        reader(illustration, textChapter("After", "After the drawing.")) {
            turnToPicture()
            tapImage("Map", 0.5f)
            waitUntil(timeoutMillis = UiTimeoutMs) { isShown(CloseImage) }
            onNodeWithContentDescription(CloseImage).performClick()
            waitUntil(timeoutMillis = UiTimeoutMs) { !isShown(CloseImage) }

            tapImage("Map", 0.95f)
            waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithText("After the drawing.").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(!isShown(CloseImage), "an edge tap opened the picture instead of turning")
        }

    @Test
    fun `a floated picture sits beside its text and still opens from a tap`() = reader(
        TestChapter(
            "Floated",
            listOf("Bobo reloaded his slingshot. ".repeat(30)),
            ImageRef("OEBPS/bobo.png", "Bobo", 300, 600, columnWidth = 0.4f),
            BlockFloat(FloatSide.Right),
        ),
    ) {
        awaitImage("Bobo")
        assertTrue(onAllNodesWithText("Bobo reloaded", substring = true).fetchSemanticsNodes().isNotEmpty(), "the text shares the picture's page")
        val shown = onAllNodesWithContentDescription("Bobo").onFirst().getUnclippedBoundsInRoot()
        assertTrue(shown.width < (width / 2).dp, "the float kept its share of the column: ${shown.width}")
        assertTrue(shown.left > (width / 2).dp, "and its side: ${shown.left}")
        tapImage("Bobo", 0.5f)
        waitUntil(timeoutMillis = UiTimeoutMs) { isShown(CloseImage) }
    }

    @Test
    fun `a chapter that is one picture opens from a tap too`() = reader(pictureChapter("Plate")) {
        awaitImage("Plate")
        tapImage("Plate", 0.5f)
        waitUntil(timeoutMillis = UiTimeoutMs) { isShown(CloseImage) }
    }

    private fun reader(vararg chapters: TestChapter, test: ComposeUiTest.() -> Unit) {
        val id = "image-${UUID.randomUUID()}"
        val server = StubServer(packRoutes(id, chapters.toList()) + ("/api/v1/admin/books/$id/reader-progress" to { _ -> """{"progress_percent":0}""" }))
        try {
            runUiTest(width = width, height = 900) {
                setContent {
                    KobappTheme(formFactor = FormFactor.Desktop) {
                        ReaderScreen(
                            book = Book(id = id, title = "Illustrated", author = "A", coverUrl = ""),
                            api = KobblerApi(HttpClient(), server.url, "t"),
                            formFactor = FormFactor.Desktop,
                            onBack = {},
                        )
                    }
                }
                waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithText("Ch 1/", substring = true).fetchSemanticsNodes().isNotEmpty() }
                waitForIdle()
                test()
            }
        } finally {
            server.close()
        }
    }

    private fun ComposeUiTest.isShown(description: String) =
        onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.awaitImage(description: String) {
        waitUntil(timeoutMillis = UiTimeoutMs) { isShown(description) }
        waitForIdle()
    }

    private fun ComposeUiTest.tapImage(description: String, across: Float) {
        val image = onAllNodesWithContentDescription(description).onFirst().getUnclippedBoundsInRoot()
        val x = image.left.value + image.width.value * across
        val y = image.top.value + image.height.value / 2
        onRoot().performTouchInput { click(Offset(x, y)) }
        waitForIdle()
    }

    private fun ComposeUiTest.turnToPicture(description: String = "Map") {
        waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithText("A paragraph before", substring = true).fetchSemanticsNodes().isNotEmpty() }
        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        awaitImage(description)
    }

    private companion object {
        const val CloseImage = "Close image"
    }
}
