package dev.kobbler.app.ui.dashboard

import dev.kobbler.app.data.DiscoverItem
import dev.kobbler.app.data.DiscoverSearchResults
import dev.kobbler.app.data.SearchHitDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a query looks like when read top to bottom.
 *
 * The house this is built for has tens of thousands of books, so the library answers a
 * two-word query with a section for every type it holds. Discover after all of them is
 * Discover nobody sees.
 */
class SearchSectionsTest {

    private fun hit(type: String, id: String, title: String, year: Int? = null) =
        SearchHitDto(type = type, id = id, title = title, year = year)

    private fun item(title: String, year: Int? = null, id: String = title) = DiscoverItem(
        provider = "tmdb", providerId = id, mediaType = "tv", title = title, year = year,
        description = null, posterUrl = "", backdropUrl = null, genres = emptyList(),
        rating = null, ratingCount = null, authors = emptyList(), libraryId = null,
        wantStatus = null, isInLibrary = false,
    )

    private fun labels(sections: List<SearchSection>) = sections.map {
        when (it) {
            is SearchSection.Library -> "lib:${it.type}"
            is SearchSection.Discover -> "disc:${it.mediaType}"
        }
    }

    @Test
    fun `a discovered show is read with the shows, not after the bookshelf`() {
        val sections = searchSections(
            libraryHits = listOf(
                hit("book", "b1", "Last Seen Wearing"),
                hit("author", "a1", "Last Seen"),
                hit("tv_show", "s1", "Last Seen Alive"),
                hit("movie", "m1", "Last Seen"),
            ),
            discover = DiscoverSearchResults(
                movies = emptyList(),
                tvShows = listOf(item("Last Seen", 2026)),
                books = emptyList(),
            ),
        )

        assertEquals(
            listOf("lib:movie", "lib:tv_show", "disc:tv", "lib:book", "lib:author"),
            labels(sections),
        )
    }

    @Test
    fun `a title the library already holds is not offered twice`() {
        val sections = searchSections(
            libraryHits = listOf(hit("tv_show", "s1", "Last Seen", 2026)),
            discover = DiscoverSearchResults(
                movies = emptyList(),
                tvShows = listOf(item("Last  seen!", 2026), item("Last Seen", 2019, id = "other")),
                books = emptyList(),
            ),
        )

        val discovered = sections.filterIsInstance<SearchSection.Discover>().single()
        assertEquals(listOf("other"), discovered.items.map { it.providerId })
    }

    @Test
    fun `entry focus lands on the first row of the first section`() {
        val sections = searchSections(
            libraryHits = listOf(hit("book", "b1", "Last Seen Wearing")),
            discover = DiscoverSearchResults(
                movies = emptyList(),
                tvShows = listOf(item("Last Seen", 2026, id = "42")),
                books = emptyList(),
            ),
        )

        assertEquals("disc.tmdb.42", sections.first().firstRowKey)
        assertTrue(sections.none { it is SearchSection.Discover && it.items.isEmpty() }, "an empty section was kept")
    }
}
