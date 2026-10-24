package dev.kobbler.app.ui.dashboard

import dev.kobbler.app.runUiTest
import dev.kobbler.app.UiTimeoutMs
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.dashboard.home.HomeWide
import dev.kobbler.app.ui.dashboard.home.RecentCardTag
import dev.kobbler.app.ui.dashboard.home.UpNextCardTag
import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Home's first screen is Up Next and the newest arrivals, with the picture behind them rather than above. */
@OptIn(ExperimentalTestApi::class)
class HomeWideLayoutTest {

    private val discover = buildString {
        append("""{"watch_next":[""")
        append((1..4).joinToString(",") {
            """{"media_type":"movie","media_id":"m$it","title":"Queued $it","link":"/movies/m$it","progress_percent":40,"position_seconds":60}"""
        })
        append("""],"recently_added":[""")
        append((1..8).joinToString(",") {
            """{"item_type":"movie","id":"r$it","title":"Recent $it","subtitle":"2024","link":"/movies/r$it"}"""
        })
        append("""],"sections":[""")
        append((1..6).joinToString(",") { section ->
            val items = (1..8).joinToString(",") {
                """{"provider":"tmdb","provider_id":"$section-$it","media_type":"movie","title":"Pick $section.$it","year":2024}"""
            }
            """{"title":"Shelf $section","section_key":"movie_shelf_$section","sort_order":$section,"items":[$items]}"""
        })
        append("]}")
    }

    private fun home(formFactor: FormFactor, width: Int, height: Int, test: DesktopComposeUiTest.() -> Unit) =
        runUiTest(width = width, height = height) {
            StubServer(mapOf("/api/v1/admin/discover" to { _ -> discover })).use { stub ->
                val api = KobblerApi(HttpClient(), stub.url, "token")
                val homeData = kotlinx.coroutines.runBlocking { api.getDiscover() }
                setContent {
                    KobappTheme(formFactor = formFactor) {
                        HomeWide(
                            homeData = homeData,
                            api = api,
                            onContinueClick = {},
                            onRecentClick = {},
                            onDiscoverItemClick = {},
                        )
                    }
                }
                waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithText("Recent 1").fetchSemanticsNodes().isNotEmpty() }
                waitForIdle()
                test()
            }
        }

    @Test
    fun `tv shows recently added without scrolling`() = home(FormFactor.TV, width = 960, height = 540) {
        val top = onAllNodesWithText("Recent 1").onFirst().getUnclippedBoundsInRoot().top
        assertTrue(top < 540.dp, "Recently Added starts at $top of a 540dp screen")
    }

    /** An Up Next card is a Recently Added poster turned on its side. */
    @Test
    fun `up next cards are the posters below turned sideways`() {
        for ((formFactor, width, height) in listOf(Triple(FormFactor.Desktop, 1920, 1080), Triple(FormFactor.TV, 960, 540))) {
            home(formFactor, width, height) {
                val card = onNodeWithTag("$UpNextCardTag.1").getUnclippedBoundsInRoot()
                val poster = onNodeWithTag("$RecentCardTag.1").getUnclippedBoundsInRoot()
                assertEquals(poster.width.value, card.height.value, 2f, "$formFactor: card $card against poster $poster")
                assertEquals(poster.width.value * 1.5f, card.width.value, 2f, "$formFactor: card $card against poster $poster")
            }
        }
    }

    /** The backdrop stays put and keeps following focus however far down the rows go. */
    @Test
    fun `the backdrop shows behind the rows far down the page`() = home(FormFactor.TV, width = 960, height = 540) {
        onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToIndex(7)
        onAllNodesWithText("Pick 6.1").onFirst().performSemanticsAction(SemanticsActions.RequestFocus)
        val heading = { onAllNodesWithText("Shelf 6").onFirst().getUnclippedBoundsInRoot() }
        waitUntil(timeoutMillis = UiTimeoutMs) { heading().bottom in 0.dp..540.dp }
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        val pixels = onRoot().captureToImage().toPixelMap()
        val y = ((heading().top + heading().bottom) / 2).value.toInt().coerceIn(0, pixels.height - 1)
        val behind = (pixels.width * 3 / 4 until pixels.width).map { pixels[it, y] }.toSet()
        assertTrue(behind.size > 1, "the right of the Shelf 6 heading is one flat colour: the backdrop is gone")
    }
}
