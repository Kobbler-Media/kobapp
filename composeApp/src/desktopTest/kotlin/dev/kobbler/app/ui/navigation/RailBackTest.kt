package dev.kobbler.app.ui.navigation

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * BACK pressed while the TV rail is open. Leaving the app is only right from the plain
 * Home screen: a page opened from Home still reports Home as its tab, and treating that
 * as "already home" closed the app with no way back to the Home list.
 */
class RailBackTest {

    private val discoverPage = Destination.DiscoverItemDetail("tv", "tmdb", "42")

    @Test
    fun `back from the rail lands on plain Home and only leaves from there`() {
        val cases = listOf(
            listOf(discoverPage) to true,
            listOf(Destination.Tab.Movies, Destination.MovieDetail("m1")) to true,
            listOf(Destination.Tab.Movies) to true,
            emptyList<Destination>() to false,
        )
        for ((pushed, handled) in cases) {
            val nav = NavigationState()
            pushed.forEach(nav::navigate)

            assertEquals(handled, nav.backFromRail(), "handled after $pushed")
            assertEquals(Destination.Tab.Home, nav.current, "landed after $pushed")
            assertEquals(false, nav.goBack(), "Home is not the root after $pushed")
        }
    }
}
