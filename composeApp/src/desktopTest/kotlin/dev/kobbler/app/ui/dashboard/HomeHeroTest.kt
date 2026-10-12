package dev.kobbler.app.ui.dashboard

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.DesktopComposeUiTest
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.dashboard.home.HomeHeroTag
import dev.kobbler.app.ui.dashboard.home.HomeWide
import androidx.compose.ui.test.onNodeWithTag
import dev.kobbler.app.ui.navigation.KobTopBar
import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.getUnclippedBoundsInRoot

/** The open stretch of backdrop above Up Next, under Home's floating top bar. */
@OptIn(ExperimentalTestApi::class)
class HomeHeroTest {

    private val discover =
        """{"watch_next":[""" +
            (1..3).joinToString(",") {
                """{"media_type":"movie","media_id":"m$it","title":"Queued $it","link":"/movies/m$it","progress_percent":40}"""
            } +
            """],"recently_added":[{"item_type":"movie","id":"r1","title":"Recent 1","subtitle":"2024","link":"/movies/r1"}],"sections":[]}"""

    private fun heroPage(
        formFactor: FormFactor = FormFactor.Desktop,
        width: Int = 1280,
        height: Int = 900,
        test: DesktopComposeUiTest.() -> Unit,
    ) = runDesktopComposeUiTest(width = width, height = height) {
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
                        topBar = { KobTopBar(transparent = it) },
                    )
                }
            }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Queued 1").fetchSemanticsNodes().isNotEmpty() }
            test()
        }
    }

    private fun DesktopComposeUiTest.heroBounds() = onNodeWithTag(HomeHeroTag).getUnclippedBoundsInRoot()

    /** The hero is decoration; the rows are what the page is for, so the next one shows in full. */
    @Test
    fun `desktop hero leaves the next row on screen`() = heroPage {
        val heading = onNodeWithText("Recently Added").getUnclippedBoundsInRoot()
        assertTrue(heading.bottom < 900.dp, "Recently Added is below the fold: $heading")
        assertTrue(heroBounds().bottom <= 600.dp, "the hero takes over two thirds of the window: ${heroBounds()}")
    }

    /** The backdrop is behind the whole page, so the hero's words and the strip open it. */
    @Test
    fun `the hero and its strip open the page`() {
        for (formFactor in listOf(FormFactor.Desktop, FormFactor.TV)) {
            heroPage(formFactor) {
                val strip = onNodeWithText("Up Next").getUnclippedBoundsInRoot()
                assertEquals(0.dp, heroBounds().top, "$formFactor: the hero does not start the page")
                assertTrue(strip.bottom <= heroBounds().bottom, "$formFactor: Up Next sits outside the hero")
            }
        }
    }
}
