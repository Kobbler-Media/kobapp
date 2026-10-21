package dev.kobbler.app.ui.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class NavigationReplaceTest {

    private val discover = Destination.DiscoverItemDetail("movie", "tmdb", "603")
    private val library = Destination.MovieDetail("m1")

    private fun NavigationState.backStack(): List<Destination> = buildList {
        add(current)
        while (goBack()) add(current)
    }

    @Test
    fun `a replaced entry is gone from the back stack, wherever it sits`() {
        val nav = NavigationState().apply {
            navigate(discover)
            navigate(Destination.PersonDetail("p1"))
            replace(discover, library)
        }
        assertEquals(listOf(Destination.PersonDetail("p1"), library, Destination.Tab.Home), nav.backStack())
    }

    @Test
    fun `replacing onto the same page beneath it collapses the two`() {
        val nav = NavigationState().apply {
            navigate(library)
            navigate(discover)
            replace(discover, library)
        }
        assertEquals(listOf(library, Destination.Tab.Home), nav.backStack())
    }

    @Test
    fun `an entry no longer on the stack is not resurrected`() {
        val nav = NavigationState().apply {
            navigate(discover)
            goBack()
            replace(discover, library)
        }
        assertEquals(Destination.Tab.Home, nav.current)
        assertFalse(nav.goBack())
    }
}
