package dev.kobbler.app.ui.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.UiTimeoutMs
import dev.kobbler.app.data.Book
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.Movie
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.runUiTest
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import kotlin.test.Test

/** A page reused for another title starts that title's Want afresh; one item's want never marks the next. */
@OptIn(ExperimentalTestApi::class)
class WantFollowsItemTest {

    private fun DesktopComposeUiTest.wantThenSwitch(wantPath: String, switch: () -> Unit, stub: StubServer) {
        performAction("Want")
        waitUntil(timeoutMillis = UiTimeoutMs) { wantPath in stub.requestBodies }
        waitUntil(timeoutMillis = UiTimeoutMs) { rowHas("Wanted") }
        switch()
        waitForIdle()
        kotlin.test.assertFalse(rowHas("Wanted"), "the next title is not marked wanted")
        assertOfferedOnce("Want")
    }

    private fun stub(path: String) = StubServer(mapOf<String, (HttpExchange) -> String>(path to { _ -> "{}" }))

    @Test
    fun `book page`() = runUiTest(width = 1280, height = 900) {
        stub("/api/v1/library/books/b1/want").use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            var id by mutableStateOf("b1")
            setContent {
                KobappTheme(formFactor = FormFactor.Desktop) {
                    BookDetailScreen(
                        book = Book(id = id, title = "Thud!", author = "Pratchett", coverUrl = "", isInLibrary = false),
                        api = api, formFactor = FormFactor.Desktop, onBack = {}, onRead = {}, onListen = {},
                    )
                }
            }
            wantThenSwitch("/api/v1/library/books/b1/want", { id = "b2" }, stub)
        }
    }

    @Test
    fun `movie page`() = runUiTest(width = 1280, height = 900) {
        stub("/api/v1/admin/movies/m1/want").use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            var id by mutableStateOf("m1")
            setContent {
                KobappTheme(formFactor = FormFactor.Desktop) {
                    MovieDetailScreen(
                        movie = Movie(id = id, title = "Film", year = 2020, posterUrl = ""),
                        api = api, formFactor = FormFactor.Desktop, onBack = {}, onPlay = {}, onWatchedChanged = {},
                    )
                }
            }
            wantThenSwitch("/api/v1/admin/movies/m1/want", { id = "m2" }, stub)
        }
    }
}
