package dev.kobbler.app.ui.dashboard

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.BackdropArtTag
import dev.kobbler.app.ui.components.DataCache
import dev.kobbler.app.ui.components.cachedArtLoader
import dev.kobbler.app.ui.player.StubServer
import dev.kobbler.kobfocus.FocusScopeHost
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals

/** The page picture runs up under the title and the All/Authors/Series strip, as Movies' does. */
@OptIn(ExperimentalTestApi::class)
class BooksBackdropTest {

    private val books = """{"items":[{"id":"b1","title":"Thud!","cover_url":"/c/b1.jpg","authors":[{"id":"a1","name":"Pratchett"}]}],"total_count":1}"""
    private val dashboard = """{"recent_books":[{"id":"r1","title":"Recent","cover_url":"/c/r1.jpg","authors":[]}]}"""

    @Test
    fun `the backdrop starts at the top of the page`() {
        for (formFactor in listOf(FormFactor.TV, FormFactor.Desktop)) for (audio in listOf(false, true)) {
            runDesktopComposeUiTest(width = 960, height = 540) {
                DataCache.clear()
                SingletonImageLoader.setUnsafe(cachedArtLoader())
                try {
                    StubServer(
                        mapOf(
                            "/api/v1/library/books" to { _ -> books },
                            "/api/v1/admin/dashboard" to { _ -> dashboard },
                        ),
                    ).use { stub ->
                        val api = KobblerApi(HttpClient(), stub.url, "token")
                        setContent {
                            KobappTheme(formFactor = formFactor) {
                                FocusScopeHost(id = "books") {
                                    BooksScreen(
                                        api = api, audiobooksOnly = audio, formFactor = formFactor, onBookClick = {},
                                        onAuthorClick = {}, onSeriesClick = {},
                                    )
                                }
                            }
                        }
                        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag(BackdropArtTag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
                        val top = onAllNodesWithTag(BackdropArtTag, useUnmergedTree = true).onFirst().getUnclippedBoundsInRoot().top
                        assertEquals(0.dp, top, "$formFactor audio=$audio")
                    }
                } finally {
                    SingletonImageLoader.reset()
                }
            }
        }
    }
}
