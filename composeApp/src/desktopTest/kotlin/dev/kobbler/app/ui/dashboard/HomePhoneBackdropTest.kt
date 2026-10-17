package dev.kobbler.app.ui.dashboard

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.swipeUp
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.dashboard.home.HomePhone
import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertTrue

/** Touch Home opens on its hero's picture and moves to a card from the rail a scroll stops on. */
@OptIn(ExperimentalTestApi::class)
class HomePhoneBackdropTest {

    private val discover = buildString {
        append("""{"watch_next":[""")
        append((1..3).joinToString(",") {
            """{"media_type":"movie","media_id":"m$it","title":"Queued $it","link":"/movies/m$it","progress_percent":40,"backdrop_url":"/img/q$it-backdrop"}"""
        })
        append("""],"recently_added":[],"sections":[""")
        append((1..6).joinToString(",") { section ->
            val items = (1..6).joinToString(",") {
                """{"provider":"tmdb","provider_id":"$section-$it","media_type":"movie","title":"Pick $section.$it","year":2024,"poster_url":"/img/p$section-$it-poster","backdrop_url":"/img/p$section-$it-backdrop"}"""
            }
            """{"title":"Shelf $section","section_key":"movie_shelf_$section","sort_order":$section,"items":[$items]}"""
        })
        append("]}")
    }

    @Test
    fun `a scroll moves the backdrop to the rail it stops on`() = runDesktopComposeUiTest(width = 400, height = 800) {
        StubServer(mapOf("/api/v1/admin/discover" to { _ -> discover })).use { stub ->
            val requested = { stub.requestBodies.keys.toSet() }
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val homeData = kotlinx.coroutines.runBlocking { api.getDiscover() }
            setContent {
                KobappTheme(formFactor = FormFactor.Phone) {
                    HomePhone(homeData = homeData, api = api, onContinueClick = {}, onRecentClick = {}, onDiscoverItemClick = {})
                }
            }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Queued 1").fetchSemanticsNodes().isNotEmpty() }
            waitUntil(timeoutMillis = 10_000) { "/img/q1-backdrop" in requested() }
            val railBackdrops = { requested().filter { it.startsWith("/img/p") && it.endsWith("-backdrop") } }
            assertTrue(railBackdrops().isEmpty(), "a rail backdrop loaded before any scroll: ${railBackdrops()}")

            repeat(3) {
                onAllNodes(hasScrollToIndexAction()).onFirst().performTouchInput { swipeUp() }
                mainClock.advanceTimeBy(1_000)
                waitForIdle()
            }
            repeat(50) {
                if (railBackdrops().isNotEmpty()) return@use
                // The swap waits on virtual time, which waitUntil alone does not move.
                mainClock.advanceTimeBy(1_000)
                waitForIdle()
                Thread.sleep(100)
            }
            assertTrue(railBackdrops().isNotEmpty(), "no rail backdrop loaded after scrolling")
        }
    }
}
