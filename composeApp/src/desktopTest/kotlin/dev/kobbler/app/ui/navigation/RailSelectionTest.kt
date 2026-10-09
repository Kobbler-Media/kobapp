package dev.kobbler.app.ui.navigation

import kotlin.test.Test
import kotlin.test.assertEquals

private val tabs = listOf(
    Destination.Tab.Home,
    Destination.Tab.Movies,
    Destination.Tab.TvShows,
    Destination.Tab.Settings,
)

class RailSelectionTest {

    @Test
    fun `highlight follows the rail cursor, not the loaded tab`() {
        assertEquals(
            Destination.Tab.TvShows.section,
            railSelection(onRail = true, sidebarIdx = 2, tabs = tabs, currentTab = Destination.Tab.Home),
        )
    }

    @Test
    fun `off the rail the loaded tab wins`() {
        assertEquals(
            Destination.Tab.Home.section,
            railSelection(onRail = false, sidebarIdx = 2, tabs = tabs, currentTab = Destination.Tab.Home),
        )
    }

    @Test
    fun `the search row leaves the loaded tab highlighted`() {
        assertEquals(
            Destination.Tab.Movies.section,
            railSelection(onRail = true, sidebarIdx = -1, tabs = tabs, currentTab = Destination.Tab.Movies),
        )
    }

    @Test
    fun `an index past the end leaves the loaded tab highlighted`() {
        assertEquals(
            Destination.Tab.Movies.section,
            railSelection(onRail = true, sidebarIdx = 99, tabs = tabs, currentTab = Destination.Tab.Movies),
        )
    }
}
