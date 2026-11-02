package dev.kobbler.app.ui.dashboard

import dev.kobbler.app.runUiTest
import dev.kobbler.app.UiTimeoutMs
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.semantics.SemanticsActions
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.dashboard.home.HomePhone
import dev.kobbler.app.ui.dashboard.home.HomeWide
import dev.kobbler.app.ui.dashboard.home.UpNextCardTag
import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertTrue

/** Up Next is where a title that will never be finished keeps turning up; holding it is how it goes. */
@OptIn(ExperimentalTestApi::class)
class UpNextMenuTest {

    private val discover =
        """{"watch_next":[""" +
            """{"media_type":"movie","media_id":"m1","title":"A Film","link":"/movies/m1","progress_percent":40},""" +
            """{"media_type":"event","media_id":"race","title":"MotoGP","subtitle":"Malaysia GP","link":"/sports/events/race","progress_percent":10},""" +
            """{"media_type":"episode","media_id":"e7","title":"A Show","subtitle":"S1E7","link":"/tv/episodes/e7","progress_percent":10}""" +
            """],"recently_added":[],"sections":[]}"""

    private val routes = mapOf<String, (com.sun.net.httpserver.HttpExchange) -> String>(
        "/api/v1/admin/discover" to { _ -> discover },
        "/api/v1/admin/movies/m1/watch-state" to { _ -> "{}" },
        "/api/v1/admin/tv/episodes/race/watch-state" to { _ -> "{}" },
        "/api/v1/admin/tv/episodes/race/watch-state/earlier" to { _ -> """{"updated_count":3}""" },
        "/api/v1/admin/tv/episodes/e7/watch-state" to { _ -> "{}" },
    )

    private fun home(formFactor: FormFactor, test: DesktopComposeUiTest.(StubServer) -> Unit) =
        runUiTest(width = if (formFactor == FormFactor.Phone) 412 else 1280, height = 900) {
            StubServer(routes).use { stub ->
                val api = KobblerApi(HttpClient(), stub.url, "token")
                val homeData = kotlinx.coroutines.runBlocking { api.getDiscover() }
                setContent {
                    KobappTheme(formFactor = formFactor) {
                        if (formFactor == FormFactor.Phone) {
                            HomePhone(homeData, api, onContinueClick = {}, onRecentClick = {}, onDiscoverItemClick = {})
                        } else {
                            HomeWide(homeData = homeData, api = api, onContinueClick = {}, onRecentClick = {}, onDiscoverItemClick = {})
                        }
                    }
                }
                waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithText("MotoGP").fetchSemanticsNodes().isNotEmpty() }
                test(stub)
            }
        }

    private fun DesktopComposeUiTest.pick(stub: StubServer, label: String, path: String) {
        onNodeWithText(label).performClick()
        waitUntil(timeoutMillis = UiTimeoutMs) { path in stub.requestBodies }
    }

    @Test
    fun `holding OK on an Up Next card offers to mark it and everything before it watched`() = home(FormFactor.TV) { stub ->
        onNodeWithTag("$UpNextCardTag.1").performSemanticsAction(SemanticsActions.RequestFocus)
        waitForIdle()
        onRoot().performKeyInput { keyDown(Key.DirectionCenter) }
        mainClock.advanceTimeBy(1_000)
        // The menu is its own window and is already up by the time OK is let go.
        onAllNodes(isRoot()).onFirst().performKeyInput { keyUp(Key.DirectionCenter) }
        waitForIdle()
        pick(stub, "Mark earlier episodes watched", "/api/v1/admin/tv/episodes/race/watch-state/earlier")
    }

    @Test
    fun `a long press on a phone's Up Next card offers the same`() = home(FormFactor.Phone) { stub ->
        onNodeWithText("A Show").performTouchInput { longClick() }
        waitForIdle()
        pick(stub, "Mark watched", "/api/v1/admin/tv/episodes/e7/watch-state")
        val body = stub.requestBodies.getValue("/api/v1/admin/tv/episodes/e7/watch-state")
        assertTrue("\"watched\":true" in body, body)
    }

    @Test
    fun `a film has nothing earlier to mark`() = home(FormFactor.Phone) { stub ->
        onNodeWithText("A Film").performTouchInput { longClick() }
        waitForIdle()
        assertTrue(onAllNodesWithText("Mark earlier episodes watched").fetchSemanticsNodes().isEmpty())
        pick(stub, "Mark watched", "/api/v1/admin/movies/m1/watch-state")
    }
}
