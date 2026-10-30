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
import androidx.compose.ui.unit.dp
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.getBoundsInRoot
import dev.kobbler.app.offline.JvmOfflineFs
import dev.kobbler.app.offline.OfflineEngine
import dev.kobbler.app.offline.OfflineStore
import dev.kobbler.app.offline.Reachability
import dev.kobbler.app.ui.offline.LocalOfflineEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.nio.file.Files

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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val root = Files.createTempDirectory("book-actions").toFile()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
    }

    @Test
    fun `with both downloads beside it, the lead action still shows its name`() {
        val engine = OfflineEngine(OfflineStore(JvmOfflineFs(root), scope), Reachability(scope), scope, startProxy = { null })
        for (formFactor in listOf(FormFactor.TV, FormFactor.Desktop, FormFactor.Phone)) {
            runUiTest(width = if (formFactor == FormFactor.Phone) 412 else 1280, height = 900) {
                setContent {
                    KobappTheme(formFactor = formFactor, section = Section.Audio) {
                        CompositionLocalProvider(LocalOfflineEngine provides engine) {
                            BookDetailScreen(book = book.copy(isInLibrary = true), api = api, formFactor = formFactor, onBack = {}, onRead = {}, onListen = {})
                        }
                    }
                }
                onNodeWithContentDescription("Download audio").assertExists()
                val label = onNodeWithText("Listen", useUnmergedTree = true).getBoundsInRoot()
                assertTrue(label.right - label.left > 30.dp, "$formFactor: Listen is ${label.right - label.left} wide")
            }
        }
    }
}
