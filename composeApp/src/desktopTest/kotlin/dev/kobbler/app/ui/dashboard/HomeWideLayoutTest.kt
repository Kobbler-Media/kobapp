package dev.kobbler.app.ui.dashboard

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.dashboard.home.HomeWide
import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import kotlin.test.Test
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
        append("""],"sections":[]}""")
    }

    private fun home(formFactor: FormFactor, width: Int, height: Int, test: DesktopComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(width = width, height = height) {
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
                            onDetailsClick = { _: Int -> }.takeIf { formFactor == FormFactor.Desktop },
                        )
                    }
                }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Recent 1").fetchSemanticsNodes().isNotEmpty() }
                waitForIdle()
                test()
            }
        }

    @Test
    fun `tv shows recently added without scrolling`() = home(FormFactor.TV, width = 960, height = 540) {
        val top = onAllNodesWithText("Recent 1").onFirst().getUnclippedBoundsInRoot().top
        assertTrue(top < 540.dp, "Recently Added starts at $top of a 540dp screen")
    }

    @Test
    fun `desktop up next tiles stay tile sized`() = home(FormFactor.Desktop, width = 1920, height = 1080) {
        val left = { t: String -> onAllNodesWithText(t).onFirst().getUnclippedBoundsInRoot().left }
        val pitch = left("Queued 3") - left("Queued 2")
        assertTrue(pitch <= 260.dp, "up next pitch $pitch")
    }

    @Test
    fun `the hero follows focus into the rails`() = home(FormFactor.TV, width = 960, height = 540) {
        onAllNodesWithText("Recent 3").onFirst().performSemanticsAction(SemanticsActions.RequestFocus)
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("RECENTLY ADDED").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodesWithText("Recent 3").fetchSemanticsNodes().size >= 2, "hero title did not follow focus")
    }
}
