package dev.kobbler.app.ui.dashboard

import dev.kobbler.app.data.DiscoverItem
import dev.kobbler.app.data.DiscoverSearchResults
import dev.kobbler.app.data.IptvChannel
import dev.kobbler.app.data.SearchHitDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a query looks like when read top to bottom.
 *
 * Discover, the guide and IPTV answer well after the library, so they are read after it:
 * anything that lands above rows already on screen pushes them away. The house this is
 * built for answers a two-word query with a section for every type it holds, so the best
 * of each is read first and the rest of every section is a jump away rather than a scroll.
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

    @Test
    fun sportsLeaguesAreASectionOfTheirOwnAfterTvShows() {
        val sections = searchSections(
            listOf(
                SearchHitDto(type = "tv_show", id = "sp1", title = "MotoGP", kind = "sport"),
                SearchHitDto(type = "tv_show", id = "s1", title = "Motorheads", kind = "tv"),
            ),
            discover = null,
        )
        assertEquals(listOf("lib:tv_show", "lib:sport"), labels(sections))
        assertEquals("Sports", sectionTitle("sport"))
    }

    private fun labels(sections: List<SearchSection>) = sections.map {
        when (it) {
            is SearchSection.Library -> "lib:${it.type}"
            is SearchSection.Discover -> "disc:${it.mediaType}"
            is SearchSection.LiveChannels -> "live:channels"
            is SearchSection.LiveAirings -> "live:airings"
            is SearchSection.IptvChannels -> "iptv:channels"
        }
    }

    @Test
    fun `discover is read after every library section, so its late answer never lands above them`() {
        val sections = searchSections(
            libraryHits = listOf(
                hit("book", "b1", "Last Seen Wearing"),
                hit("author", "a1", "Last Seen"),
                hit("tv_show", "s1", "Last Seen Alive"),
                hit("movie", "m1", "Last Seen"),
            ),
            discover = DiscoverSearchResults(
                movies = listOf(item("Last Seen Again", 2020, id = "mv")),
                tvShows = listOf(item("Last Seen", 2026)),
                books = emptyList(),
            ),
        )

        assertEquals(
            listOf("lib:movie", "lib:tv_show", "lib:book", "lib:author", "disc:movie", "disc:tv"),
            labels(sections),
        )
    }

    @Test
    fun `the best of every section is read first, the library in the order it ranked them`() {
        val layout = searchLayout(
            searchSections(
                libraryHits = listOf(
                    hit("book", "b1", "Last Seen Wearing"),
                    hit("movie", "m1", "Last Seen"),
                    hit("book", "b2", "Last Seen Again"),
                ),
                discover = DiscoverSearchResults(
                    movies = listOf(item("Last Seen Alive", 2020, id = "mv")),
                    tvShows = emptyList(),
                    books = emptyList(),
                ),
            ),
        )

        assertEquals(listOf("lib:book", "lib:movie", "disc:movie"), labels(layout.top))
        assertEquals(listOf("b1", "m1", "mv"), layout.top.map { it.firstRowKey?.substringAfterLast('.') })
        assertEquals(listOf("lib:book"), labels(layout.sections), "a section with nothing left is dropped")
        assertEquals(listOf("b2"), layout.sections.filterIsInstance<SearchSection.Library>().single().hits.map { it.id })
        assertEquals("lib.book.b1", layout.firstRowKey)
    }

    @Test
    fun `a title the query names outright leads the top results, then titles it starts`() {
        val sections = searchSections(
            libraryHits = listOf(
                hit("book", "b1", "Breaking Waves"),
                hit("tv_episode", "e1", "Star Trek: Enterprise S01E08 Breaking the Ice"),
                hit("person", "p1", "Breaking Bad Wolf"),
            ),
            discover = DiscoverSearchResults(
                movies = emptyList(),
                tvShows = listOf(item("Breaking Bad", 2008, id = "bb")),
                books = emptyList(),
            ),
        )

        assertEquals(
            listOf("disc:tv", "lib:person", "lib:book", "lib:tv_episode"),
            labels(searchLayout(sections, query = "breaking  bad").top),
        )
        assertEquals(
            listOf("lib:book", "lib:person", "disc:tv", "lib:tv_episode"),
            labels(searchLayout(sections, query = "Breakin").top),
        )
    }

    @Test
    fun `every section below the top results is a jump away`() {
        val layout = searchLayout(
            searchSections(
                libraryHits = listOf(hit("movie", "m1", "One"), hit("movie", "m2", "Two")) +
                    (1..8).map { hit("tv_show", "s$it", "Show $it") },
                discover = DiscoverSearchResults(
                    movies = listOf(item("Elsewhere", 2020, id = "mv"), item("Further", 2021, id = "mv2")),
                    tvShows = emptyList(),
                    books = emptyList(),
                ),
                iptv = listOf(IptvChannel(id = "i1", name = "Channel", iconUrl = null, category = null, language = null, country = null)),
            ),
        )

        val top = SearchLeadingItems + 1 + 4
        val collapsed = searchJumps(layout, expanded = emptyMap())
        assertEquals(
            listOf(
                SearchJump("Movies", top, "lib.movie.m2"),
                SearchJump("TV Shows", top + 2, "lib.tv_show.s2"),
                SearchJump("Discover · Movies", top + 2 + 7, "disc.tmdb.mv2"),
            ),
            collapsed,
        )
        val expanded = searchJumps(layout, expanded = mapOf("lib:tv_show" to true))
        assertEquals(top + 2 + 9, expanded.last().itemIndex)
    }

    @Test
    fun `a query that answers in one section has no top results to repeat it`() {
        val layout = searchLayout(
            searchSections(
                libraryHits = emptyList(),
                discover = DiscoverSearchResults(movies = listOf(item("Elsewhere", id = "mv")), tvShows = emptyList(), books = emptyList()),
            ),
        )

        assertEquals(emptyList(), layout.top)
        assertEquals(emptyList(), searchJumps(layout, expanded = emptyMap()))
        assertEquals("disc.tmdb.mv", layout.firstRowKey)
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

        assertEquals("lib.book.b1", sections.first().firstRowKey)
        assertTrue(sections.none { it is SearchSection.Discover && it.items.isEmpty() }, "an empty section was kept")
    }
}
