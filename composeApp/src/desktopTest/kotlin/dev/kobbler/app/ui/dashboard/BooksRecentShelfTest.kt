package dev.kobbler.app.ui.dashboard

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import dev.kobbler.app.UiTimeoutMs
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.runUiTest
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.DataCache
import dev.kobbler.app.ui.player.StubServer
import dev.kobbler.kobfocus.FocusScopeHost
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class BooksRecentShelfTest {

    private val books = """{"items":[{"id":"b1","title":"Thud!","authors":[]}],"total_count":1}"""
    private val dashboard = """{
        "recent_books":[{"id":"e1","title":"Fresh Ebook","authors":""},{"id":"a1","title":"Fresh Audiobook","authors":""}],
        "recent_ebooks":[{"id":"e1","title":"Fresh Ebook","authors":""}],
        "recent_audiobooks":[{"id":"a1","title":"Fresh Audiobook","authors":""}]
    }"""

    @Test
    fun `recently added holds only the section's own format`() {
        for (audio in listOf(false, true)) runUiTest(width = 1280, height = 900) {
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
                val own = if (audio) "Fresh Audiobook" else "Fresh Ebook"
                val other = if (audio) "Fresh Ebook" else "Fresh Audiobook"
                waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithText(own).fetchSemanticsNodes().isNotEmpty() }
                assertTrue(onAllNodesWithText(other).fetchSemanticsNodes().isEmpty(), "$other shown on the ${if (audio) "audiobook" else "book"} shelf")
            }
        }
    }
}
