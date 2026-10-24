package dev.kobbler.app.ui.dashboard

import dev.kobbler.app.runUiTest
import dev.kobbler.app.UiTimeoutMs
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

@OptIn(ExperimentalTestApi::class)
class HomeForYouRowTest {

    private val discover =
        """{"watch_next":[],"recently_added":[],""" +
            """"stremio_continue":[{"provider":"tmdb","provider_id":"202","media_type":"tv","title":"Resumed Show",""" +
            """"genres":[],"identifiers":{"tmdb_id":"202"},"subtitle":"S01E03","season":1,"episode":3,""" +
            """"progress_percent":10.0,"last_watched":"2026-10-17T10:00:00Z"}],""" +
            """"for_you":[{"provider":"tmdb","provider_id":"301","media_type":"movie","title":"Picked Film",""" +
            """"genres":[],"identifiers":{"tmdb_id":"301"},"because":["Show A","Show B"]}],"sections":[]}"""

    @Test
    fun `for you row renders below stremio with its reason and opens the discover item`() {
        for (formFactor in listOf(FormFactor.Phone, FormFactor.TV)) {
            runUiTest(width = 1280, height = 1400) {
                StubServer(mapOf("/api/v1/admin/discover" to { _ -> discover })).use { stub ->
                    DataCache.clear()
                    val api = KobblerApi(HttpClient(), stub.url, "token")
                    var opened: DiscoverItem? = null
                    setContent {
                        KobappTheme(formFactor = formFactor) {
                            HomeScreen(api = api, formFactor = formFactor, onDiscoverItemClick = { opened = it })
                        }
                    }
                    waitUntil(timeoutMillis = UiTimeoutMs) {
                        onAllNodesWithText("Picked Film").fetchSemanticsNodes().isNotEmpty()
                    }
                    // A phone card is too narrow for the sentence, so it names only the title.
                    onNodeWithText(if (formFactor == FormFactor.Phone) "Like Show A" else "Because you watched Show A +1").assertExists()
                    val stremioTop = onNodeWithText("Continue Watching on Stremio").getUnclippedBoundsInRoot().top
                    val forYouTop = onNodeWithText("For You").getUnclippedBoundsInRoot().top
                    assertTrue(forYouTop > stremioTop, "$formFactor: For You at $forYouTop, Stremio at $stremioTop")

                    onNodeWithText("Picked Film").performClick()
                    waitForIdle()
                    assertEquals("301", opened?.providerId, formFactor.toString())
                    assertEquals("movie", opened?.mediaType, formFactor.toString())
                }
            }
        }
    }
}
