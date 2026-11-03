package dev.kobbler.app.watchnext

import dev.kobbler.app.data.AuthScreen
import dev.kobbler.app.data.MediaType
import dev.kobbler.app.data.Profile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WatchNextLaunchTest {
    private val launch = WatchNextLaunch("p1", "/tv/episodes/e1", "Grey's Anatomy", "S23E02 - Slippin' & Slidin")

    @Test
    fun deepLinkRoundTripsThroughItsUri() {
        val uri = launch.toUri(host = "dev.kobbler.app.dbg")
        assertEquals("kobapp://dev.kobbler.app.dbg/watch-next", uri.substringBefore('?'))
        assertEquals(launch, parseWatchNextLaunch(uri))
    }

    @Test
    fun foreignOrIncompleteUrisAreIgnored() {
        assertNull(parseWatchNextLaunch(null))
        assertNull(parseWatchNextLaunch("https://example.com/watch-next?profile=p1&link=/movies/m1"))
        assertNull(parseWatchNextLaunch("kobapp://dev.kobbler.app/other?profile=p1&link=/movies/m1"))
        assertNull(parseWatchNextLaunch("kobapp://dev.kobbler.app/watch-next?link=/movies/m1"))
        assertNull(parseWatchNextLaunch("kobapp://dev.kobbler.app/watch-next?profile=p1"))
    }

    @Test
    fun becomesAContinueItemThatResumes() {
        val item = launch.toContinueItem()
        assertEquals("/tv/episodes/e1", item.link)
        assertEquals(MediaType.TvShow, item.mediaType)
        assertEquals("Grey's Anatomy", item.title)
        assertEquals(MediaType.Movie, launch.copy(link = "/movies/m1").toContinueItem().mediaType)
        assertEquals(MediaType.SportsEvent, launch.copy(link = "/sports/events/x").toContinueItem().mediaType)
    }

    private val open = Profile(id = "p1", token = "t", apiUrl = "https://kob.example")
    private val other = Profile(id = "p2", token = "t", apiUrl = "https://kob.example")

    private data class Case(val name: String, val profiles: List<Profile>, val current: String?, val screen: AuthScreen, val expected: LaunchStep)

    private val cases = listOf(
        Case("still loading", listOf(open), "p1", AuthScreen.Loading, LaunchStep.Wait),
        Case("already in the profile", listOf(open, other), "p1", AuthScreen.LoggedIn, LaunchStep.Play),
        Case("picker shown on launch", listOf(open, other), "p1", AuthScreen.Picker, LaunchStep.SwitchTo("p1")),
        Case("in another profile", listOf(open, other), "p2", AuthScreen.LoggedIn, LaunchStep.SwitchTo("p1")),
        Case("profile was removed", listOf(other), "p2", AuthScreen.LoggedIn, LaunchStep.Drop),
        Case("signed out", emptyList(), null, AuthScreen.Login, LaunchStep.Drop),
        Case("pin profile waits on the picker", listOf(open.copy(hasPin = true), other), "p2", AuthScreen.Picker, LaunchStep.Wait),
        Case("pin profile is not entered from elsewhere", listOf(open.copy(hasPin = true), other), "p2", AuthScreen.LoggedIn, LaunchStep.Drop),
        Case("pin profile already entered", listOf(open.copy(hasPin = true)), "p1", AuthScreen.LoggedIn, LaunchStep.Play),
        Case("profile needing relink", listOf(open.copy(needsRelink = true)), null, AuthScreen.Picker, LaunchStep.Wait),
    )

    @Test
    fun launchStepFollowsTheProfileState() {
        cases.forEach { c ->
            assertEquals(c.expected, launchStep(launch, c.profiles, c.current, c.screen), c.name)
        }
    }
}
