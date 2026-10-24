package dev.kobbler.app.ui.dashboard

import dev.kobbler.app.runUiTest
import dev.kobbler.app.UiTimeoutMs
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals

/** Bulk clean-up of history that belongs to someone else. */
@OptIn(ExperimentalTestApi::class)
class WatchHistoryScreenTest {

    private val history = """{"items":[
        {"media_type":"tv","tmdb_id":1,"title":"Bluey","sources":["stremio"]},
        {"media_type":"movie","tmdb_id":2,"title":"Frozen","sources":["trakt"]}
    ],"total":2}"""
    private val both = """{"items":[{"media_type":"tv","tmdb_id":1},{"media_type":"movie","tmdb_id":2}]"""

    private fun screen(test: androidx.compose.ui.test.DesktopComposeUiTest.(StubServer) -> Unit) =
        runUiTest(width = 1280, height = 800) {
            StubServer(
                mapOf(
                    "/api/v1/admin/watch-history" to { _ -> history },
                    "/api/v1/admin/watch-history/exclude" to { _ -> "" },
                    "/api/v1/admin/watch-history/remove" to { _ -> "" },
                ),
            ).use { stub ->
                setContent {
                    KobappTheme(formFactor = FormFactor.Desktop) {
                        WatchHistoryScreen(api = KobblerApi(HttpClient(), stub.url, "token"))
                    }
                }
                waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithText("Bluey", substring = true).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("Select all").performClick()
                waitForIdle()
                test(stub)
            }
        }

    @Test
    fun `selected titles are hidden from recommendations`() = screen { stub ->
        onNodeWithText("Hide from recommendations").performClick()
        waitUntil(timeoutMillis = UiTimeoutMs) { stub.requestBodies.containsKey("/api/v1/admin/watch-history/exclude") }
        assertEquals("$both,\"excluded\":true}", stub.requestBodies["/api/v1/admin/watch-history/exclude"])
    }

    @Test
    fun `removing asks first`() = screen { stub ->
        onNodeWithText("Remove from history").performClick()
        waitForIdle()
        assertEquals(null, stub.requestBodies["/api/v1/admin/watch-history/remove"])
        onNodeWithText("Remove").performClick()
        waitUntil(timeoutMillis = UiTimeoutMs) { stub.requestBodies.containsKey("/api/v1/admin/watch-history/remove") }
        assertEquals("$both}", stub.requestBodies["/api/v1/admin/watch-history/remove"])
    }
}
