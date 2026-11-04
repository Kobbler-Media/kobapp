package dev.kobbler.app.ui.detail

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.data.Book
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.runUiTest
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.detail.tvshow.detailPage
import dev.kobbler.app.ui.navigation.Destination
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DiscoverBookPageTest {

    private val synopsis = "A lone envoy is sent to a frozen world whose people are neither man nor woman."

    private val routes = mapOf<String, (HttpExchange) -> String>(
        "/api/v1/admin/discover/book/hardcover/42" to { _ ->
            """{"media_type":"book","provider":"hardcover","details":{"title":"The Left Hand of Darkness",
               "authors":["Ursula K. Le Guin"],"description":"$synopsis","cover_url":"",
               "subjects":["Science Fiction","Classics"],"rating":4.2}}"""
        },
    )

    private fun discoverBook(formFactor: FormFactor, width: Int = 960, height: Int = 540, test: DesktopComposeUiTest.() -> Unit) =
        detailPage(Destination.DiscoverItemDetail("book", "hardcover", "42"), routes, formFactor = formFactor, width = width, height = height) {
            waitForIdle()
            test()
        }

    private fun DesktopComposeUiTest.bounds(text: String, substring: Boolean = true): DpRect =
        onNodeWithText(text, substring = substring).getUnclippedBoundsInRoot()

    private fun DesktopComposeUiTest.noDetailsPanel(label: String) =
        assertEquals(0, onAllNodesWithText("Details").fetchSemanticsNodes().size, "$label shows a Details panel")

    @Test
    fun `on a TV and a desktop window it is the library book page, with the release strip under Want`() =
        listOf(FormFactor.TV, FormFactor.Desktop).forEach { formFactor ->
            discoverBook(formFactor) {
                noDetailsPanel("$formFactor")
                onNodeWithText("Ursula K. Le Guin").assertExists()
                onNodeWithText("★ 4.2").assertExists()
                val topBarStatus = onAllNodesWithText("Not in library", ignoreCase = true).fetchSemanticsNodes()
                    .any { it.boundsInRoot.bottom < bounds("The Left Hand of Darkness").top.value }
                assertTrue(topBarStatus, "$formFactor has no Not in library status above the title")
                assertTrue(bounds("RELEASE AVAILABILITY").top > bounds("Want", substring = false).bottom, "$formFactor strip beside Want")
                if (formFactor == FormFactor.TV) assertTrue(bounds(synopsis).width > 500.dp, "synopsis squeezed to ${bounds(synopsis).width}")
            }
        }

    @Test
    fun `on a phone it stacks as the library book page does`() = discoverBook(FormFactor.Phone, width = 412, height = 900) {
        val title = bounds("The Left Hand of Darkness")
        val page = bounds(synopsis)
        assertEquals(((page.left + page.right) / 2).value, ((title.left + title.right) / 2).value, 2f, "title not centred over the page: $title")
        val want = bounds("Want", substring = false)
        val strip = bounds("RELEASE AVAILABILITY")
        assertTrue(strip.top > want.bottom, "strip $strip above Want $want")
        assertTrue(bounds(synopsis).top > strip.bottom, "synopsis above the strip")
    }

    private val libraryBook = Book(
        id = "b1", title = "The Dispossessed", author = "Ursula K. Le Guin", coverUrl = "",
        description = synopsis, genres = listOf("Science Fiction"), formats = listOf("epub"), rating = 4.1f,
    )

    private fun libraryPage(formFactor: FormFactor, width: Int, test: DesktopComposeUiTest.() -> Unit) = runUiTest(width = width, height = 900) {
        setContent {
            KobappTheme(formFactor = formFactor) {
                BookDetailScreen(
                    book = libraryBook, api = KobblerApi(HttpClient(), "http://127.0.0.1:1", "token"),
                    formFactor = formFactor, onBack = {}, onRead = {}, onListen = {},
                )
            }
        }
        waitForIdle()
        test()
    }

    @Test
    fun `a library book on a TV gives the synopsis the room a details panel took`() = libraryPage(FormFactor.TV, 960) {
        noDetailsPanel("TV")
        assertTrue(bounds(synopsis).width > 500.dp, "synopsis squeezed to ${bounds(synopsis).width}")
    }

    @Test
    fun `a wide desktop window still has room for the details panel`() = libraryPage(FormFactor.Desktop, 1600) {
        onNodeWithText("Details").assertExists()
    }
}
