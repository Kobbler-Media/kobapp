package dev.kobbler.app.ui.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import dev.kobbler.app.UiTimeoutMs
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.runUiTest
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.DataCache
import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals

/** A card the viewer has already watched carries a watched mark; the rest don't. */
@OptIn(ExperimentalTestApi::class)
class WatchedMarkTest {

    private val discover = """{"watch_next":[],"sections":[],"recently_added":[
        {"item_type":"movie","id":"m1","title":"Seen Movie","subtitle":"2024","link":"/movies/m1","watched":true},
        {"item_type":"episode","id":"e1","title":"Fresh Show","subtitle":"S01E01","link":"/tv/s1","watched":false}
    ]}"""

    private val dashboard = """{
        "recent_movies":[
            {"id":"m1","title":"Seen Movie","year":2024,"watched":true},
            {"id":"m2","title":"Fresh Movie","year":2024,"watched":false}],
        "recent_tv":[
            {"show_id":"s1","show_title":"Seen Show","season_identifier":"1","episode_id":"e1","episode_identifier":"1","watched":true},
            {"show_id":"s2","show_title":"Fresh Show","season_identifier":"1","episode_id":"e2","episode_identifier":"1","watched":false}],
        "recent_sports":[
            {"show_id":"l1","show_title":"Seen League","season_identifier":"2024","episode_id":"v1","episode_identifier":"1","watched":true},
            {"show_id":"l2","show_title":"Fresh League","season_identifier":"2024","episode_id":"v2","episode_identifier":"1","watched":false}]
    }"""

    private val empty = """{"items":[],"total_count":0}"""
    private val movies = """{"items":[{"id":"g1","title":"Grid Seen Movie","year":2020,"watched":true},{"id":"g2","title":"Grid Fresh Movie","year":2020}],"total_count":2}"""
    private val shows = """{"items":[{"id":"g3","title":"Grid Seen Show","watched":true},{"id":"g4","title":"Grid Fresh Show","watched":false}],"total_count":2}"""

    private fun showsWatchedMarks(
        formFactor: FormFactor,
        loaded: String,
        expected: Int = 1,
        dashboard: String = this.dashboard,
        grid: String = empty,
        screen: @Composable (KobblerApi) -> Unit,
    ) =
        runUiTest(width = 1280, height = 3000) {
            DataCache.clear()
            StubServer(
                mapOf(
                    "/api/v1/admin/discover" to { _ -> discover },
                    "/api/v1/admin/dashboard" to { _ -> dashboard },
                    "/api/v1/admin/movies" to { _ -> if (grid == movies) movies else empty },
                    "/api/v1/admin/tv/shows" to { _ -> if (grid == shows) shows else empty },
                    "/api/v1/admin/sports/leagues" to { _ -> if (grid == shows) shows else empty },
                ),
            ).use { stub ->
                val api = KobblerApi(HttpClient(), stub.url, "token")
                setContent { KobappTheme(formFactor = formFactor) { screen(api) } }
                waitUntilLoaded(loaded)
                assertEquals(
                    expected,
                    onAllNodes(hasContentDescription("Watched"), useUnmergedTree = true).fetchSemanticsNodes().size,
                    "$formFactor: $loaded",
                )
            }
        }

    private fun ComposeUiTest.waitUntilLoaded(text: String) =
        waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun `home recently added marks the watched card`() {
        for (formFactor in FormFactor.entries) {
            showsWatchedMarks(formFactor, "Seen Movie") { HomeScreen(api = it, formFactor = formFactor) }
        }
    }

    @Test
    fun `movies recently added marks the watched card`() =
        showsWatchedMarks(FormFactor.Desktop, "Fresh Movie") { MoviesScreen(api = it, onMovieClick = {}) }

    @Test
    fun `tv recently added marks the show whose new episode was watched`() =
        showsWatchedMarks(FormFactor.Desktop, "Fresh Show") { TvShowsScreen(api = it, onShowClick = {}) }

    @Test
    fun `sports recently added marks the league whose new event was watched`() =
        showsWatchedMarks(FormFactor.Desktop, "Fresh League") { TvShowsScreen(api = it, sportsOnly = true, onShowClick = {}) }

    private val noRecent = """{}"""

    @Test
    fun `movies grid marks the watched movie`() =
        showsWatchedMarks(FormFactor.Desktop, "Grid Fresh Movie", dashboard = noRecent, grid = movies) { MoviesScreen(api = it, onMovieClick = {}) }

    @Test
    fun `tv grid marks the fully watched show`() =
        showsWatchedMarks(FormFactor.Desktop, "Grid Fresh Show", dashboard = noRecent, grid = shows) { TvShowsScreen(api = it, onShowClick = {}) }

    @Test
    fun `sports grid marks the fully watched league`() =
        showsWatchedMarks(FormFactor.Desktop, "Grid Fresh Show", dashboard = noRecent, grid = shows) { TvShowsScreen(api = it, sportsOnly = true, onShowClick = {}) }
}
