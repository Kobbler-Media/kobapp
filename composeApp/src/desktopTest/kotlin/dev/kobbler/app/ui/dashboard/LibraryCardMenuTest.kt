package dev.kobbler.app.ui.dashboard

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import dev.kobbler.app.UiTimeoutMs
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.runUiTest
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.DataCache
import dev.kobbler.app.ui.player.StubServer
import dev.kobbler.kobfocus.FocusScopeHost
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Holding a poster in the library offers what would otherwise take a trip through its page. */
@OptIn(ExperimentalTestApi::class)
class LibraryCardMenuTest {

    private class Rig(val stub: StubServer) {
        var played: String? = null
    }

    private fun browse(shows: Boolean, test: DesktopComposeUiTest.(Rig) -> Unit) = runUiTest(width = 1280, height = 900) {
        DataCache.clear()
        val page = """{"items":[{"id":"x1","title":"Holdable","year":2020}]}"""
        StubServer(
            mapOf(
                "/api/v1/admin/movies" to { _ -> page },
                "/api/v1/admin/tv/shows" to { _ -> page },
                "/api/v1/admin/movies/genres" to { _ -> """{"genres":[]}""" },
                "/api/v1/admin/tv/shows/genres" to { _ -> """{"genres":[]}""" },
                "/api/v1/admin/discover" to { _ -> """{"watch_next":[],"recently_added":[],"sections":[]}""" },
                "/api/v1/admin/dashboard" to { _ -> "{}" },
                "/api/v1/admin/watchlist" to { _ -> "{}" },
                "/api/v1/admin/movies/x1/watch-state" to { _ -> "{}" },
            ),
        ).use { stub ->
            val rig = Rig(stub)
            val api = KobblerApi(HttpClient(), stub.url, "token")
            setContent {
                KobappTheme(formFactor = FormFactor.Desktop) {
                    FocusScopeHost(id = "browse") {
                        if (shows) TvShowsScreen(api = api, onShowClick = {}, onShowPlay = { rig.played = it.id })
                        else MoviesScreen(api = api, onMovieClick = {}, onMoviePlay = { rig.played = it.id })
                    }
                }
            }
            waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithText("Holdable").fetchSemanticsNodes().isNotEmpty() }
            test(rig)
        }
    }

    private fun DesktopComposeUiTest.hold() {
        onAllNodesWithText("Holdable").onFirst().performTouchInput { longClick() }
        waitForIdle()
    }

    private fun DesktopComposeUiTest.pick(rig: Rig, label: String, path: String) {
        onNodeWithText(label).performClick()
        waitUntil(timeoutMillis = UiTimeoutMs) { path in rig.stub.requestBodies }
    }

    @Test
    fun `play goes straight to playback`() {
        for (shows in listOf(false, true)) browse(shows) { rig ->
            hold()
            onNodeWithText("Play").performClick()
            assertEquals("x1", rig.played, if (shows) "show" else "movie")
        }
    }

    @Test
    fun `add to my list`() {
        for (shows in listOf(false, true)) browse(shows) { rig ->
            hold()
            pick(rig, "Add to my list", "/api/v1/admin/watchlist")
            val body = rig.stub.requestBodies.getValue("/api/v1/admin/watchlist")
            assertTrue(""""media_type":"${if (shows) "show" else "movie"}"""" in body, body)
        }
    }

    @Test
    fun `a film can be marked watched from its poster`() = browse(shows = false) { rig ->
        hold()
        pick(rig, "Mark watched", "/api/v1/admin/movies/x1/watch-state")
    }
}
