package dev.kobbler.app.ui.detail

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.Author
import dev.kobbler.app.data.Book
import dev.kobbler.app.data.DiscoverDetail
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.Movie
import dev.kobbler.app.data.Person
import dev.kobbler.app.data.TvShow
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.player.GlobalPlayerState
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertTrue

/** A phone turned sideways has the width for the poster-beside-text page; a stack runs every line across it. */
@OptIn(ExperimentalTestApi::class)
class DetailPhoneLayoutTest {

    private val prose = "A long synopsis that wraps over several lines whichever way the page is laid out. ".repeat(6)
    private val api = KobblerApi(HttpClient(), "http://127.0.0.1:1", "token")

    private val screens: Map<String, @Composable () -> Unit> = mapOf(
        "movie" to {
            MovieDetailScreen(
                movie = Movie(id = "m1", title = "Movie", year = 2020, posterUrl = "", synopsis = prose),
                api = api, formFactor = FormFactor.Phone, onBack = {}, onPlay = {}, onWatchedChanged = {},
            )
        },
        "show" to {
            TvShowDetailScreen(
                show = TvShow(id = "s1", title = "Show", year = 2020, posterUrl = "", synopsis = prose),
                formFactor = FormFactor.Phone, onBack = {}, onPlayEpisode = { _, _, _ -> },
            )
        },
        "discover" to {
            DiscoverDetailScreen(
                detail = DiscoverDetail(
                    mediaType = "movie", provider = "tmdb", title = "Found", year = 2026,
                    description = prose, posterUrl = "", backdropUrl = null, genres = emptyList(),
                    rating = null, cast = emptyList(), network = null, runtime = null,
                    authors = emptyList(), libraryId = null, wantStatus = null, isInLibrary = false,
                    identifiers = emptyMap(), providerId = "42",
                ),
                api = api, formFactor = FormFactor.Phone, player = null, gps = GlobalPlayerState(),
                onBack = {}, onViewInLibrary = { _, _ -> },
            )
        },
        "book" to {
            BookDetailScreen(
                book = Book(id = "b1", title = "Book", author = "Writer", coverUrl = "", description = prose),
                api = api, formFactor = FormFactor.Phone, onBack = {}, onRead = {}, onListen = {},
            )
        },
        "person" to {
            PersonDetailScreen(
                person = Person(
                    id = "p1", name = "Person", imageUrl = null, bio = prose, birthDate = null,
                    deathDate = null, knownForDepartment = null, placeOfBirth = null,
                ),
                filmography = emptyList(), api = api, onBack = {}, formFactor = FormFactor.Phone,
            )
        },
        "author" to {
            AuthorDetailScreen(
                author = Author(id = "a1", name = "Author", bio = prose),
                books = emptyList(), formFactor = FormFactor.Phone, onBack = {}, onBookClick = {}, onSeriesClick = {},
            )
        },
    )

    private fun proseWidth(screen: @Composable () -> Unit, width: Int, height: Int): Dp {
        var measured = 0.dp
        runDesktopComposeUiTest(width = width, height = height) {
            setContent { KobappTheme(formFactor = FormFactor.Phone) { screen() } }
            waitForIdle()
            measured = onAllNodesWithText(prose.take(40), substring = true).onFirst().getUnclippedBoundsInRoot().let { it.right - it.left }
        }
        return measured
    }

    @Test
    fun `landscape phone sets prose beside the art`() {
        for ((name, screen) in screens) {
            val w = proseWidth(screen, width = 900, height = 400)
            assertTrue(w <= 720.dp, "$name prose spans $w of a 900dp screen, as if nothing sat beside it")
        }
    }

    @Test
    fun `portrait phone keeps the stacked page`() {
        for ((name, screen) in screens) {
            val w = proseWidth(screen, width = 412, height = 900)
            assertTrue(w >= 340.dp, "$name prose squeezed to $w of a 412dp screen")
        }
    }
}
