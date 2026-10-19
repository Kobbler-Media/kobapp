package dev.kobbler.app.ui.dashboard

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.kobbler.app.data.DiscoverItem
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.DataCache
import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Stremio's Continue Watching sits under Recently Added and opens like any discover tile. */
@OptIn(ExperimentalTestApi::class)
class HomeStremioRowTest {

    private val discover =
        """{"watch_next":[],""" +
            """"recently_added":[{"item_type":"movie","id":"r1","title":"Recent 1","subtitle":"2024","link":"/movies/r1"}],""" +
            """"stremio_continue":[{"provider":"tmdb","provider_id":"202","media_type":"tv","title":"Resumed Show",""" +
            """"genres":[],"identifiers":{"tmdb_id":"202"},"subtitle":"S01E03 - Ep Three","season":1,"episode":3,""" +
            """"progress_percent":10.0,"last_watched":"2026-10-17T10:00:00Z"}],"sections":[]}"""

    @Test
    fun `stremio row renders below recently added and opens the discover item`() {
        for (formFactor in listOf(FormFactor.Phone, FormFactor.TV)) {
            runDesktopComposeUiTest(width = 1280, height = 1400) {
                StubServer(mapOf("/api/v1/admin/discover" to { _ -> discover })).use { stub ->
                    DataCache.clear()
                    val api = KobblerApi(HttpClient(), stub.url, "token")
                    var opened: DiscoverItem? = null
                    setContent {
                        KobappTheme(formFactor = formFactor) {
                            HomeScreen(api = api, formFactor = formFactor, onDiscoverItemClick = { opened = it })
                        }
                    }
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithText("Resumed Show").fetchSemanticsNodes().isNotEmpty()
                    }
                    onNodeWithText("S01E03 - Ep Three").assertExists()
                    val recentTop = onNodeWithText("Recently Added").getUnclippedBoundsInRoot().top
                    val stremioTop = onNodeWithText("Continue Watching on Stremio").getUnclippedBoundsInRoot().top
                    assertTrue(stremioTop > recentTop, "$formFactor: Stremio row at $stremioTop, Recently Added at $recentTop")

                    onNodeWithText("Resumed Show").performClick()
                    waitForIdle()
                    assertEquals("202", opened?.providerId, formFactor.toString())
                    assertEquals("tv", opened?.mediaType, formFactor.toString())
                }
            }
        }
    }
}
