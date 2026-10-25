package dev.kobbler.app.ui.detail.tvshow

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import dev.kobbler.app.ui.navigation.Destination
import dev.kobbler.app.ui.navigation.NavigationState
import dev.kobbler.app.ui.player.GlobalPlayerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A season page has to open in one frame's worth of work whatever the season's length:
 * every row composed up front cost a Chromecast well over a second before the first frame.
 */
@OptIn(ExperimentalTestApi::class)
class LongSeasonTest {

    private val episodeCount = 40

    // Far enough down that the page's head has scrolled away and the rows around it are
    // recycled ones, which is what a restore has to cope with.
    private val farEpisode = 15

    private val longSeason = buildString {
        append("""{"id":"show-1","title":"Long Show","year":2015,"kids_allowed":false,"seasons":[""")
        append("""{"id":"season-1","season_identifier":"1","season_sort_order":1,"episodes":[""")
        append(
            (1..episodeCount).joinToString(",") { e ->
                """{"id":"e1-$e","episode_identifier":"$e","episode_sort_order":$e,"title":"S1E$e title",
                   "runtime":45,"is_in_library":true,"watched":false}"""
            },
        )
        append("""]}],"continue_episode":{"season_idx":0,"episode_idx":0,"episode_id":"e1-1","position_seconds":600}}""")
    }

    private fun DesktopComposeUiTest.listed(title: String) =
        onAllNodesWithText(title, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun DesktopComposeUiTest.downToEpisode(n: Int): String {
        press(Key.DirectionDown)
        assertEquals("Season 1", focusedText())
        moveUntil(Key.DirectionDown, { it?.contains("S1E$n title") == true }, maxSteps = n + 2)
        val row = focusedText()
        assertTrue(row?.contains("S1E$n title") == true, "never reached episode $n, at $row")
        return row!!
    }

    @Test
    fun `episodes below the fold are not composed until the remote reaches them`() = showPage(json = longSeason) {
        assertTrue(listed("S1E1 title"), "first episode listed")
        assertTrue(!listed("S1E$farEpisode title"), "episode $farEpisode composed before it was in view")
        assertTrue(!listed("S1E$episodeCount title"), "last episode composed before it was in view")
        downToEpisode(farEpisode)
        assertTrue(!listed("S1E1 title"), "first episode still composed with the list scrolled past it")
    }

    @Test
    fun `focus returns to an episode far down the list when the player closes`() {
        val gps = GlobalPlayerState()
        showPage(json = longSeason, gps = gps) {
            val row = downToEpisode(farEpisode)
            runOnIdle { gps.isFullscreen = true }
            mainClock.advanceTimeBy(500)
            waitForIdle()
            runOnIdle { gps.isFullscreen = false }
            assertFocusSettlesOn(row)
        }
    }

    @Test
    fun `focus returns to an episode far down the list when the page is rebuilt`() {
        val nav = NavigationState()
        showPage(json = longSeason, nav = nav) {
            val row = downToEpisode(farEpisode)
            runOnIdle { nav.navigate(Destination.TvShowDetail("show-2")) }
            mainClock.advanceTimeBy(1_000)
            waitForIdle()
            assertTrue(!listed("S1E$farEpisode title"), "the show page outlived its destination")
            runOnIdle { nav.goBack() }
            assertFocusSettlesOn(row)
        }
    }
}
