package dev.kobbler.app.ui.detail

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.detail.tvshow.SHOW_PATH
import dev.kobbler.app.ui.detail.tvshow.showJson
import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

private const val SHOW_STATE = "$SHOW_PATH/watch-state"

@OptIn(ExperimentalTestApi::class)
class ShowActionsTest {

    private fun showPage(test: DesktopComposeUiTest.(StubServer) -> Unit) =
        runDesktopComposeUiTest(width = 1280, height = 900) {
            StubServer(mapOf(SHOW_PATH to { _ -> showJson() }, SHOW_STATE to { _ -> """{"updated_count":6}""" })).use { stub ->
                val api = KobblerApi(HttpClient(), stub.url, "token")
                val show = runBlocking { api.getShow("show-1") }
                setContent {
                    KobappTheme(formFactor = FormFactor.Desktop) {
                        TvShowDetailScreen(show = show, formFactor = FormFactor.Desktop, onBack = {}, onPlayEpisode = { _, _, _ -> }, api = api)
                    }
                }
                waitForIdle()
                test(stub)
            }
        }

    @Test
    fun `rewatch unwatches the whole show and resume goes back to the first episode`() = showPage { stub ->
        onNodeWithText("S03 E02", substring = true).assertExists()
        onNodeWithContentDescription("More").performClick()
        onNodeWithText("Rewatch from the start").performClick()
        onNodeWithText("Start over").performClick()
        waitUntil(timeoutMillis = 5_000) { stub.requestBodies[SHOW_STATE] != null }
        assertTrue(stub.requestBodies.getValue(SHOW_STATE).contains("\"watched\":false"), stub.requestBodies.getValue(SHOW_STATE))
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("S01 E01").fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithText("Resume").assertCountEquals(0)
        onAllNodesWithText("0 / 12 watched", ignoreCase = true).assertCountEquals(1)
    }

    @Test
    fun `season actions live on the season strip, not in the show menu`() = showPage {
        onNodeWithContentDescription("More").performClick()
        onAllNodesWithText("Mark season", substring = true).assertCountEquals(0)
        onAllNodesWithText("Want season", substring = true).assertCountEquals(0)
        onNodeWithText("Rewatch from the start").assertExists()
        onNodeWithText("Rewatch from the start").performClick()
        onNodeWithText("Cancel").performClick()

        onNodeWithContentDescription("Season 3 options").performClick()
        onNodeWithText("Mark season 3 watched").assertExists()
    }
}
