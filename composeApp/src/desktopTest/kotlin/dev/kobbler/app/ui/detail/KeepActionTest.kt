package dev.kobbler.app.ui.detail

import dev.kobbler.app.runUiTest
import dev.kobbler.app.UiTimeoutMs
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.Movie
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.player.GlobalPlayerState
import dev.kobbler.app.ui.player.StubServer
import dev.kobbler.app.ui.player.rememberKeepAction
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/** A title last watched as a debrid stream offers to keep that stream, from its page. */
@OptIn(ExperimentalTestApi::class)
class KeepActionTest {

    private val routes = mapOf<String, (HttpExchange) -> String>(
        "/api/v1/library/stats" to { _ -> """{"kobload_configured":true}""" },
        "/api/v1/admin/stream/recent" to { _ ->
            """[{"id":"s-old","media_type":"movie","media_id":"m2","release_title":"Other","debrid_provider":"rd","filename":"o.mkv"},
                {"id":"s9","media_type":"movie","media_id":"m1","release_title":"Film","debrid_provider":"rd","filename":"f.mkv"}]"""
        },
        "/api/v1/admin/stream/s9/keep" to { _ -> """{"job_id":"j","state":"queued"}""" },
    )

    @Test
    fun `the movie page keeps the stream it last played`() = runUiTest(width = 1280, height = 800) {
        StubServer(routes).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            runBlocking { api.checkStreamingAvailability() }
            val gps = GlobalPlayerState()
            setContent {
                KobappTheme(formFactor = FormFactor.Desktop) {
                    MovieDetailScreen(
                        movie = Movie(id = "m1", title = "Film", year = 2020, posterUrl = ""),
                        api = api, formFactor = FormFactor.Desktop, onBack = {}, onPlay = {}, onWatchedChanged = {},
                        onStream = {},
                        keep = rememberKeepAction(api, gps, "movie", setOf("m1")),
                    )
                }
            }
            waitUntil(timeoutMillis = UiTimeoutMs) { "/api/v1/admin/stream/recent" in stub.requestBodies }
            waitForIdle()
            onAllNodes(hasContentDescription("More") or hasText("More"))[0].performClick()
            waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithText("Keep in library").fetchSemanticsNodes().isNotEmpty() }
            onAllNodesWithText("Keep in library")[0].performClick()
            waitUntil(timeoutMillis = UiTimeoutMs) { "/api/v1/admin/stream/s9/keep" in stub.requestBodies }
        }
    }
}
