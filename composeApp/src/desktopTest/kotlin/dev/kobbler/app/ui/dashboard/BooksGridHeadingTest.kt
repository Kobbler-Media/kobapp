package dev.kobbler.app.ui.dashboard

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.DataCache
import dev.kobbler.app.ui.player.StubServer
import dev.kobbler.kobfocus.FocusScopeHost
import io.ktor.client.HttpClient
import kotlin.test.Test

/** Below Recently Added, the full list reads as a section of its own, with the library's size. */
@OptIn(ExperimentalTestApi::class)
class BooksGridHeadingTest {

    private val books = """{"items":[{"id":"b1","title":"Thud!","authors":[{"id":"a1","name":"Pratchett"}]}],"total_count":412}"""
    private val dashboard = """{"recent_books":[{"id":"r1","title":"Recent","authors":[]}]}"""

    @Test
    fun `the grid has a heading that carries the library size`() {
        for (audio in listOf(false, true)) runDesktopComposeUiTest(width = 1280, height = 900) {
            DataCache.clear()
            StubServer(
                mapOf(
                    "/api/v1/library/books" to { _ -> books },
                    "/api/v1/admin/dashboard" to { _ -> dashboard },
                ),
            ).use { stub ->
                val api = KobblerApi(HttpClient(), stub.url, "token")
                setContent {
                    KobappTheme(formFactor = FormFactor.Desktop) {
                        FocusScopeHost(id = "books") {
                            BooksScreen(api = api, audiobooksOnly = audio, formFactor = FormFactor.Desktop, onBookClick = {})
                        }
                    }
                }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Thud!").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText(if (audio) "All audiobooks" else "All books").assertExists()
                onNodeWithText("412").assertExists()
            }
        }
    }
}
