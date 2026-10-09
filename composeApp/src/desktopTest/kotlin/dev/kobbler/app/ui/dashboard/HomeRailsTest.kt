package dev.kobbler.app.ui.dashboard

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertTrue
import androidx.compose.ui.test.ScrollWheel
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.performMouseInput

@OptIn(ExperimentalTestApi::class)
class HomeRailsTest {

    private val railLength = 14

    private val discover = buildString {
        append("""{"watch_next":[""")
        append((1..railLength).joinToString(",") {
            """{"media_type":"movie","media_id":"m$it","title":"Queued $it","link":"/movies/m$it","progress_percent":40}"""
        })
        append("""],"recently_added":[""")
        append((1..railLength).joinToString(",") {
            """{"item_type":"movie","id":"r$it","title":"Recent $it","subtitle":"2024","link":"/movies/r$it"}"""
        })
        append("""],"sections":[{"title":"Trending","section_key":"trending_movies","sort_order":0,"items":[""")
        append((1..railLength).joinToString(",") {
            """{"provider":"tmdb","provider_id":"$it","media_type":"movie","title":"Pick $it","year":2020}"""
        })
        append("]}]}")
    }

    private fun ComposeUiTest.showHome(stub: StubServer, formFactor: FormFactor) {
        val api = KobblerApi(HttpClient(), stub.url, "token")
        setContent {
            KobappTheme(formFactor = formFactor) {
                HomeScreen(api = api, formFactor = formFactor)
            }
        }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Queued 1").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeUiTest.assertRailReachesEnd(first: String, last: String) {
        onAllNodes(hasScrollAction() and hasAnyDescendant(hasText(first)))
            .onLast()
            .performScrollToIndex(railLength - 1)
        onNodeWithText(last).assertIsDisplayed()
    }

    @Test
    fun `every home rail scrolls to its last item`() {
        for (formFactor in FormFactor.entries) {
            for ((first, last) in listOf("Recent 1" to "Recent $railLength", "Pick 1" to "Pick $railLength")) {
                // Tall enough that every rail is laid out: the phone layout's cards are
                // half the default window's height.
                runDesktopComposeUiTest(width = 1280, height = 3000) {
                    StubServer(mapOf("/api/v1/admin/discover" to { _ -> discover })).use { stub ->
                        showHome(stub, formFactor)
                        assertRailReachesEnd(first, last)
                    }
                }
            }
        }
    }

    /** A trackpad swipe sideways is how a laptop walks a rail. */
    @Test
    fun `desktop rails scroll sideways with the trackpad`() {
        for (first in listOf("Queued 1", "Recent 1", "Pick 1")) {
            runDesktopComposeUiTest(width = 1280, height = 3000) {
                StubServer(mapOf("/api/v1/admin/discover" to { _ -> discover })).use { stub ->
                    showHome(stub, FormFactor.Desktop)
                    val card = onAllNodesWithText(first).onLast()
                    val before = card.getUnclippedBoundsInRoot().left
                    onAllNodes(hasScrollAction() and hasAnyDescendant(hasText(first))).onLast()
                        .performMouseInput {
                            moveTo(center)
                            scroll(1f, ScrollWheel.Horizontal)
                        }
                    waitForIdle()
                    assertTrue(card.getUnclippedBoundsInRoot().left < before, "$first's rail did not move")
                }
            }
        }
    }
}
