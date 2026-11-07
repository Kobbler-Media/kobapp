package dev.kobbler.app.ui.detail

import dev.kobbler.app.runUiTest
import dev.kobbler.app.UiTimeoutMs
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.detail.tvshow.SHOW_PATH
import dev.kobbler.app.ui.detail.tvshow.showJson
import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import androidx.compose.runtime.CompositionLocalProvider
import dev.kobbler.app.offline.JvmOfflineFs
import dev.kobbler.app.offline.OfflineEngine
import dev.kobbler.app.offline.OfflineItem
import dev.kobbler.app.offline.OfflineKind
import dev.kobbler.app.offline.OfflineState
import dev.kobbler.app.offline.OfflineStore
import dev.kobbler.app.offline.Reachability
import dev.kobbler.app.ui.offline.LocalOfflineEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.nio.file.Files
import kotlin.test.assertTrue

private const val SHOW_STATE = "$SHOW_PATH/watch-state"

@OptIn(ExperimentalTestApi::class)
class ShowActionsTest {

    private fun showPage(engine: OfflineEngine? = null, json: String = showJson(), test: DesktopComposeUiTest.(StubServer) -> Unit) =
        runUiTest(width = 1280, height = 900) {
            StubServer(mapOf(SHOW_PATH to { _ -> json }, SHOW_STATE to { _ -> """{"updated_count":6}""" })).use { stub ->
                val api = KobblerApi(HttpClient(), stub.url, "token")
                val show = runBlocking { api.getShow("show-1") }
                setContent {
                    KobappTheme(formFactor = FormFactor.Desktop) {
                        CompositionLocalProvider(LocalOfflineEngine provides engine) {
                            TvShowDetailScreen(show = show, formFactor = FormFactor.Desktop, onBack = {}, onPlayEpisode = { _, _, _ -> }, api = api)
                        }
                    }
                }
                waitForIdle()
                test(stub)
            }
        }

    @Test
    fun `rewatch unwatches the whole show and resume goes back to the first episode`() = showPage { stub ->
        onNodeWithText("S03 E02", substring = true).assertExists()
        performAction("Rewatch from the start")
        onNodeWithText("Start over").performClick()
        waitUntil(timeoutMillis = UiTimeoutMs) { stub.requestBodies[SHOW_STATE] != null }
        assertTrue(stub.requestBodies.getValue(SHOW_STATE).contains("\"watched\":false"), stub.requestBodies.getValue(SHOW_STATE))
        waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithText("S01 E01").fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithText("Resume").assertCountEquals(0)
        onAllNodesWithText("0 / 12 watched", ignoreCase = true).assertCountEquals(1)
    }

    @Test
    fun `a streamed episode left part-way resumes with its progress`() =
        showPage(json = showJson(position = 600, inLibrary = { s, _ -> s < 3 })) {
            onNodeWithText("Resume").assertExists()
            onAllNodesWithText("Stream").assertCountEquals(0)
            onNodeWithText("35m left").assertExists()
            assertOfferedOnce("From start")
        }

    @Test
    fun `the show's row offers no season actions`() = showPage {
        openOverflowIfAny()
        onAllNodesWithText("Mark season", substring = true).assertCountEquals(0)
        onAllNodesWithText("Want season", substring = true).assertCountEquals(0)
        onAllNodesWithContentDescription("Mark season", substring = true).assertCountEquals(0)
    }

    @Test
    fun `season actions live on the season strip`() = showPage {
        performAction("Rewatch from the start")
        onNodeWithText("Cancel").performClick()

        onNodeWithContentDescription("Season 3 options").performClick()
        onNodeWithText("Mark season 3 watched").assertExists()
    }

    @Test
    fun `offline, the show plays an episode that is on this device`() {
        for ((downloaded, online, label, tag) in listOf(
            Quad(listOf("e1-1", "e3-3", "e4-1"), false, "Play", "S03 E03"),
            Quad(listOf("e1-1"), false, "Play", "S01 E01"),
            Quad(listOf("e3-2", "e4-1"), false, "Resume", "S03 E02"),
            Quad(listOf("e3-3"), true, "Resume", "S03 E02"),
        )) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val root = Files.createTempDirectory("offline-show").toFile()
            val engine = OfflineEngine(OfflineStore(JvmOfflineFs(root), scope), Reachability(scope), scope, startProxy = { null })
            engine.store.update { idx ->
                idx.copy(items = downloaded.associate { id ->
                    OfflineItem.keyOf(OfflineKind.Episode, id) to OfflineItem(OfflineKind.Episode, id, id, showId = "show-1", state = OfflineState.Complete)
                })
            }
            engine.reachability.attach { online }
            runBlocking { engine.reachability.check() }
            try {
                showPage(engine) {
                    onNodeWithText(label, useUnmergedTree = true).assertExists("$downloaded")
                    onAllNodesWithText(tag, substring = true, useUnmergedTree = true).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty(), "$downloaded $tag") }
                }
            } finally {
                scope.cancel()
                root.deleteRecursively()
            }
        }
    }

    private data class Quad(val downloaded: List<String>, val online: Boolean, val label: String, val tag: String)
}
