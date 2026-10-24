package dev.kobbler.app.ui.dashboard

import dev.kobbler.app.runUiTest
import dev.kobbler.app.UiTimeoutMs
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.fontSize
import dev.kobbler.app.ui.dashboard.home.HomePhone
import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A phone turned sideways is twice as wide, not a reason for cards twice the size. */
@OptIn(ExperimentalTestApi::class)
class HomePhoneLayoutTest {

    private val discover = buildString {
        append("""{"watch_next":[""")
        append((1..5).joinToString(",") {
            """{"media_type":"movie","media_id":"m$it","title":"Queued $it","link":"/movies/m$it","progress_percent":40}"""
        })
        append("""],"recently_added":[""")
        append((1..8).joinToString(",") {
            """{"item_type":"movie","id":"r$it","title":"Recent $it","subtitle":"2024","link":"/movies/r$it"}"""
        })
        append("""],"sections":[]}""")
    }

    private fun phoneHome(width: Int, height: Int, test: DesktopComposeUiTest.() -> Unit) =
        runUiTest(width = width, height = height) {
            StubServer(mapOf("/api/v1/admin/discover" to { _ -> discover })).use { stub ->
                val api = KobblerApi(HttpClient(), stub.url, "token")
                val homeData = kotlinx.coroutines.runBlocking { api.getDiscover() }
                setContent {
                    KobappTheme(formFactor = FormFactor.Phone) {
                        HomePhone(homeData, api, onContinueClick = {}, onRecentClick = {}, onDiscoverItemClick = {})
                    }
                }
                waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithText("Queued 1").fetchSemanticsNodes().isNotEmpty() }
                test()
            }
        }

    private fun DesktopComposeUiTest.pitch(first: String, second: String): Dp =
        onNodeWithText(second).getUnclippedBoundsInRoot().left - onNodeWithText(first).getUnclippedBoundsInRoot().left

    @Test
    fun `landscape rails show more cards rather than bigger ones`() = phoneHome(width = 900, height = 2000) {
        val poster = pitch("Recent 1", "Recent 2")
        assertTrue(poster <= 150.dp, "poster pitch $poster")
        val upNext = pitch("Queued 2", "Queued 3")
        assertTrue(upNext <= 280.dp, "up next pitch $upNext")
    }

    @Test
    fun `portrait rails keep three posters and a peek`() = phoneHome(width = 412, height = 900) {
        val poster = pitch("Recent 1", "Recent 2")
        assertTrue(poster in 115.dp..125.dp, "poster pitch $poster")
    }

    @Test
    fun `landscape hero leaves room for the page below it`() = phoneHome(width = 900, height = 400) {
        val titleBottom = onNodeWithText("Queued 1").getUnclippedBoundsInRoot().bottom
        assertTrue(titleBottom <= 240.dp, "hero title ends at $titleBottom of a 400dp screen")
    }

    @Test
    fun `hero and up next titles sit on the rails' type scale`() = phoneHome(width = 412, height = 900) {
        val rail = fontSize("Recent 1")
        assertEquals(rail, fontSize("Queued 2"), "up next card title")
        val hero = fontSize("Queued 1")
        assertTrue(hero.value <= rail.value + 2, "hero title $hero against rail $rail")
    }
}
