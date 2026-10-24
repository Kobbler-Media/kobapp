package dev.kobbler.app.ui.detail

import dev.kobbler.app.runUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import dev.kobbler.app.data.Book
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.theme.Section
import io.ktor.client.HttpClient
import kotlin.test.Test

/** The labelled pill is the page's lead action; the rest are captioned icons. */
@OptIn(ExperimentalTestApi::class)
class BookActionsTest {

    private val book = Book(id = "b1", title = "Thud!", author = "Pratchett", coverUrl = "", hasAudiobook = true, formats = listOf("epub"))
    private val api = KobblerApi(HttpClient(), "http://127.0.0.1:1", "token")

    private fun leadsWith(section: Section, lead: String, other: String) = runUiTest(width = 1280, height = 900) {
        setContent {
            KobappTheme(formFactor = FormFactor.Desktop, section = section) {
                BookDetailScreen(book = book, api = api, formFactor = FormFactor.Desktop, onBack = {}, onRead = {}, onListen = {})
            }
        }
        onNodeWithText(lead).assertExists()
        onNodeWithContentDescription(other).assertExists()
    }

    @Test
    fun `from books, reading leads`() = leadsWith(Section.Books, lead = "Read", other = "Listen")

    @Test
    fun `from audiobooks, listening leads`() = leadsWith(Section.Audio, lead = "Listen", other = "Read")
}
