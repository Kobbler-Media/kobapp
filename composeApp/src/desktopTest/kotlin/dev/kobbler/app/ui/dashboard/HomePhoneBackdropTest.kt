package dev.kobbler.app.ui.dashboard

import dev.kobbler.app.runUiTest
import dev.kobbler.app.UiTimeoutMs
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.ShownAsBackground
import dev.kobbler.app.ui.components.PageBackdropTag
import dev.kobbler.app.ui.dashboard.home.HomePhone
import dev.kobbler.app.ui.navigation.TabScreenWithTopBar
import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Touch Home: the picture runs up under the bars, opens on the hero, follows a scroll to the
 * rail it stops on or a swipe along one rail, and comes back to the hero at the top. The
 * card it comes from is marked.
 */
@OptIn(ExperimentalTestApi::class)
class HomePhoneBackdropTest {

    private val discover = buildString {
        append("""{"watch_next":[""")
        append((1..3).joinToString(",") {
            """{"media_type":"movie","media_id":"m$it","title":"Queued $it","link":"/movies/m$it","progress_percent":40,"backdrop_url":"/img/q$it-backdrop"}"""
        })
        append("""],"recently_added":[],"sections":[""")
        append((1..6).joinToString(",") { section ->
            val items = (1..8).joinToString(",") {
                """{"provider":"tmdb","provider_id":"$section-$it","media_type":"movie","title":"Pick $section.$it","year":2024,"poster_url":"/img/p$section-$it-poster","backdrop_url":"/img/p$section-$it-backdrop"}"""
            }
            """{"title":"Shelf $section","section_key":"movie_shelf_$section","sort_order":$section,"items":[$items]}"""
        })
        append("]}")
    }

    private fun home(test: DesktopComposeUiTest.() -> Unit) = runUiTest(width = 400, height = 800) {
        StubServer(mapOf("/api/v1/admin/discover" to { _ -> discover })).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val homeData = kotlinx.coroutines.runBlocking { api.getDiscover() }
            setContent {
                KobappTheme(formFactor = FormFactor.Phone) {
                    TabScreenWithTopBar(onMenuClick = {}, onSearchClick = {}, formFactor = FormFactor.Phone) {
                        HomePhone(homeData = homeData, api = api, onContinueClick = {}, onRecentClick = {}, onDiscoverItemClick = {})
                    }
                }
            }
            waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithText("Queued 1").fetchSemanticsNodes().isNotEmpty() }
            test()
        }
    }

    /** The title of the card marked as the page's picture. */
    private fun DesktopComposeUiTest.shownTitle(): String? =
        onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, ShownAsBackground))
            .fetchSemanticsNodes().firstOrNull()
            ?.config?.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text

    // The swap waits on virtual time, which waitUntil alone does not move.
    private fun DesktopComposeUiTest.awaitShown(match: (String) -> Boolean) {
        repeat(40) {
            if (shownTitle()?.let(match) == true) return
            mainClock.advanceTimeBy(200)
            waitForIdle()
            Thread.sleep(20)
        }
        assertTrue(false, "the marked card never matched; it is ${shownTitle()}")
    }

    @Test
    fun `the picture runs up under the bars`() = home {
        val backdrop = onNodeWithTag(PageBackdropTag).getUnclippedBoundsInRoot()
        val menu = onNodeWithContentDescription("Menu").getUnclippedBoundsInRoot()
        assertTrue(backdrop.top <= 0.dp && backdrop.top < menu.top, "backdrop $backdrop starts below the bar's $menu")
    }

    @Test
    fun `scrolling moves the picture and the top brings the hero back`() = home {
        awaitShown { it == "Queued 1" }
        repeat(3) {
            onAllNodes(hasScrollToIndexAction()).onFirst().performTouchInput { swipeUp() }
            mainClock.advanceTimeBy(1_000)
            waitForIdle()
        }
        awaitShown { it.startsWith("Pick ") }
        var heroBack = false
        repeat(8) {
            if (heroBack) return@repeat
            onAllNodes(hasScrollToIndexAction()).onFirst().performTouchInput { swipeDown() }
            mainClock.advanceTimeBy(1_000)
            waitForIdle()
            heroBack = shownTitle() == "Queued 1"
        }
        assertTrue(heroBack, "back at the top the picture is ${shownTitle()}")
    }

    @Test
    fun `swiping along a rail follows that rail`() = home {
        val rails = onAllNodes(hasScrollToIndexAction() and hasAnyDescendant(hasText("Pick 1.1")))
        rails[rails.fetchSemanticsNodes().size - 1].performTouchInput { swipeLeft() }
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        awaitShown { it.startsWith("Pick 1.") && it != "Pick 1.1" }
    }
}
