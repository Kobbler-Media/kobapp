package dev.kobbler.app.ui.dashboard

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.DataCache
import dev.kobbler.app.ui.dashboard.home.HomeHeroTag
import dev.kobbler.app.ui.dashboard.home.HomeWide
import androidx.compose.ui.test.onNodeWithTag
import dev.kobbler.app.ui.navigation.KobTopBar
import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The hero runs under Home's top bar instead of starting below it, and the bar carries the fullscreen toggle. */
@OptIn(ExperimentalTestApi::class)
class HomeTopBarTest {

    private fun discover(watchNext: Int) = buildString {
        append("""{"watch_next":[""")
        append((1..watchNext).joinToString(",") {
            """{"media_type":"movie","media_id":"m$it","title":"Queued $it","link":"/movies/m$it","progress_percent":40}"""
        })
        append("""],"recently_added":[{"item_type":"movie","id":"r1","title":"Recent 1","subtitle":"2024","link":"/movies/r1"}],"sections":[]}""")
    }

    @Test
    fun `home page runs under the top bar only when it has a hero`() {
        for (watchNext in listOf(3, 0)) {
            runDesktopComposeUiTest(width = 1280, height = 900) {
                val hasHero = watchNext > 0
                StubServer(mapOf("/api/v1/admin/discover" to { _ -> discover(watchNext) })).use { stub ->
                    var toggled = 0
                    val api = KobblerApi(HttpClient(), stub.url, "token")
                    // HomeWide rather than HomeScreen: the screen's process-wide cache would hand
                    // the second case the first one's data.
                    val homeData = api.getDiscover()
                    setContent {
                        KobappTheme(formFactor = FormFactor.Desktop) {
                            HomeWide(
                                homeData = homeData,
                                api = api,
                                onContinueClick = {},
                                onRecentClick = {},
                                onDiscoverItemClick = {},
                                topBar = { transparent ->
                                    KobTopBar(transparent = transparent, onToggleFullscreen = { toggled++ })
                                },
                            )
                        }
                    }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(if (hasHero) "Queued 1" else "Recent 1").fetchSemanticsNodes().isNotEmpty() }

                    val bar = onNodeWithText("Search library & Discover").getUnclippedBoundsInRoot()
                    val firstItem = if (hasHero) onNodeWithTag(HomeHeroTag).getUnclippedBoundsInRoot()
                    else onAllNodes(hasScrollAction()).onFirst().onChildren().onFirst().getUnclippedBoundsInRoot()
                    if (hasHero) assertEquals(0.dp, firstItem.top) else assertTrue(firstItem.top >= bar.bottom)

                    onNodeWithContentDescription("Fullscreen").performClick()
                    assertEquals(1, toggled)
                }
            }
        }
    }

    @Test
    fun `top bar stays up while home cannot show its page`() {
        runDesktopComposeUiTest(width = 1280, height = 900) {
            DataCache.clear()
            StubServer(emptyMap()).use { stub ->
                val api = KobblerApi(HttpClient(), stub.url, "token")
                setContent {
                    KobappTheme(formFactor = FormFactor.Desktop) {
                        HomeScreen(api = api, formFactor = FormFactor.Desktop, topBar = { KobTopBar(transparent = it) })
                    }
                }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Retry", ignoreCase = true).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("Search library & Discover").assertIsDisplayed()
            }
        }
    }
}
