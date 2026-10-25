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
 * built for answers a two-word query with a section for every type it holds, so each of
 * those later groups is a jump away rather than a scroll.
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
    fun `each later-arriving group offers a jump to its first row`() {
        val sections = searchSections(
            libraryHits = listOf(hit("movie", "m1", "One"), hit("movie", "m2", "Two")) +
                (1..7).map { hit("tv_show", "s$it", "Show $it") },
            discover = DiscoverSearchResults(
                movies = listOf(item("Elsewhere", 2020, id = "mv")),
                tvShows = emptyList(),
                books = emptyList(),
            ),
            iptv = listOf(IptvChannel(id = "i1", name = "Channel", iconUrl = null, category = null, language = null, country = null)),
        )

        val collapsed = searchJumps(sections, expanded = emptyMap())
        assertEquals(
            listOf(SearchJump(SearchGroup.Discover, 11, "disc.tmdb.mv"), SearchJump(SearchGroup.Iptv, 13, "iptv.ch.i1")),
            collapsed,
        )
        val expanded = searchJumps(sections, expanded = mapOf("lib:tv_show" to true))
        assertEquals(listOf(13, 15), expanded.map { it.itemIndex })
    }

    @Test
    fun `nothing to jump to when the later group is already at the top`() {
        val sections = searchSections(
            libraryHits = emptyList(),
            discover = DiscoverSearchResults(movies = listOf(item("Elsewhere", id = "mv")), tvShows = emptyList(), books = emptyList()),
        )

        assertEquals(emptyList(), searchJumps(sections, expanded = emptyMap()))
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
