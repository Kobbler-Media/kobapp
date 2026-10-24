package dev.kobbler.app.ui.navigation

import dev.kobbler.app.ui.dashboard.SearchScope
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * BACK from a page opened out of search returns to search, query intact — not to whatever
 * tab happened to be underneath. Search is not a Destination, so the stack alone lands on Home.
 */
class SearchBackTest {

    private val movie = Destination.MovieDetail("wall-e")
    private val person = Destination.PersonDetail("andrew-stanton")

    private fun searchedFor(query: String, dest: Destination) = NavigationState().apply {
        searchOpen = true
        searchQuery = query
        navigateFromSearch(dest)
    }

    @Test
    fun `back from a search result reopens search with its query`() {
        val nav = searchedFor("wall", movie)
        assertEquals(false, nav.searchOpen)

        assertEquals(true, nav.goBack())
        assertEquals(true, nav.searchOpen)
        assertEquals("wall", nav.searchQuery)
        assertEquals(Destination.Tab.Home, nav.current)
    }

    @Test
    fun `pages opened beyond the result pop normally before search returns`() {
        val nav = searchedFor("wall", movie)
        nav.navigate(person)

        nav.goBack()
        assertEquals(movie to false, nav.current to nav.searchOpen)
        nav.goBack()
        assertEquals(true, nav.searchOpen)
    }

    @Test
    fun `search opens scoped to the section it was opened from`() {
        val nav = NavigationState().apply { navigate(Destination.Tab.LiveTv) }
        nav.openSearch()
        assertEquals(SearchScope.LiveTv, nav.searchScope)

        nav.searchScope = SearchScope.All
        nav.navigateFromSearch(movie)
        nav.goBack()
        assertEquals(SearchScope.All, nav.searchScope)

        nav.searchOpen = false
        nav.navigate(Destination.Tab.Movies)
        nav.openSearch()
        assertEquals(SearchScope.Movies, nav.searchScope)

        nav.openSearch(from = Destination.Tab.Audiobooks)
        assertEquals(SearchScope.Books, nav.searchScope)

        nav.openSearch(from = Destination.Tab.Iptv)
        assertEquals(SearchScope.Iptv, nav.searchScope)
    }

    @Test
    fun `search is not reopened once the stack has moved on`() {
        val viaTab = searchedFor("wall", movie).apply { navigate(Destination.Tab.Movies) }
        assertEquals(false, viaTab.goBack())
        assertEquals(false, viaTab.searchOpen)

        val plain = NavigationState().apply { navigate(movie) }
        plain.goBack()
        assertEquals(false, plain.searchOpen)

        val twice = searchedFor("wall", movie)
        twice.goBack()
        twice.searchOpen = false
        twice.navigate(person)
        twice.goBack()
        assertEquals(false, twice.searchOpen)
    }
}
