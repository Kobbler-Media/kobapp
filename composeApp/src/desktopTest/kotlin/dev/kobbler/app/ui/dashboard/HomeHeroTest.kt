package dev.kobbler.app.ui.dashboard

import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.semantics.SemanticsActions
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.dashboard.home.HomeWide
import dev.kobbler.app.ui.navigation.KobTopBar
import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onFirst

/** The desktop hero's Resume/Details row under Home's floating top bar. */
@OptIn(ExperimentalTestApi::class)
class HomeHeroTest {

    private val discover =
        """{"watch_next":[""" +
            (1..3).joinToString(",") {
                """{"media_type":"movie","media_id":"m$it","title":"Queued $it","link":"/movies/m$it","progress_percent":40}"""
            } +
            """],"recently_added":[{"item_type":"movie","id":"r1","title":"Recent 1","subtitle":"2024","link":"/movies/r1"}],"sections":[]}"""

    private lateinit var focusManager: FocusManager

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
                focusManager = LocalFocusManager.current
                KobappTheme(formFactor = formFactor) {
                    HomeWide(
                        homeData = homeData,
                        api = api,
                        onContinueClick = {},
                        onRecentClick = {},
                        onDiscoverItemClick = {},
                        onDetailsClick = { _: Int -> }.takeIf { formFactor == FormFactor.Desktop },
                        topBar = { KobTopBar(transparent = it) },
                    )
                }
            }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Queued 1").fetchSemanticsNodes().isNotEmpty() }
            test()
        }
    }

    private fun SemanticsNodeInteraction.focus() = performSemanticsAction(SemanticsActions.RequestFocus)

    @Test
    fun `right past the last hero action stays on it`() = heroPage {
        onNodeWithText("Details").focus()
        // What Navigation's content zone does with a D-pad press.
        runOnIdle { focusManager.moveFocus(FocusDirection.Right) }
        val focused = onAllNodes(isFocused()).fetchSemanticsNodes().map { it.config }
        assertEquals(1, focused.size, "focus left the page")
        onNodeWithText("Details").assertIsFocused()
    }

    private fun DesktopComposeUiTest.heroBounds() =
        onAllNodes(hasScrollAction()).onFirst().onChildren().onFirst().getUnclippedBoundsInRoot()

    /** The hero is decoration; the rows are what the page is for, so the next one shows in full. */
    @Test
    fun `desktop hero leaves the next row on screen`() = heroPage {
        val heading = onNodeWithText("Recently Added").getUnclippedBoundsInRoot()
        assertTrue(heading.bottom < 900.dp, "Recently Added is below the fold: $heading")
        assertTrue(heroBounds().bottom <= 600.dp, "the hero takes over two thirds of the window: ${heroBounds()}")
    }

    @Test
    fun `the backdrop runs under the up next row`() {
        for (formFactor in listOf(FormFactor.Desktop, FormFactor.TV)) {
            heroPage(formFactor) {
                val strip = onNodeWithText("UP NEXT").getUnclippedBoundsInRoot()
                assertTrue(strip.bottom <= heroBounds().bottom, "$formFactor: Up Next sits below the backdrop")
            }
        }
    }
}
