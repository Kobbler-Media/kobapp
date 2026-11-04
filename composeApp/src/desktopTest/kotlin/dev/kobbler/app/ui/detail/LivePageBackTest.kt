package dev.kobbler.app.ui.detail

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.LiveChannel
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.runUiTest
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.player.StubServer
import dev.kobbler.kobfocus.FocusScopeHost
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class LivePageBackTest {

    private val channel = LiveChannel(
        id = "c3", name = "SBS", lcn = "3", callsign = null, logoUrl = null,
        network = "SBS", region = null, kind = null, hd = false, enabled = true,
        favourite = false, muxLabel = null, typeLabel = null, hasGuide = true,
        guideEntryCount = 0, videoCodec = null, audioCodec = null, signalQuality = null,
    )

    private val routes = mapOf<String, (HttpExchange) -> String>(
        "/api/v1/admin/livetv/recordings/r1" to { _ -> """{"id":"r1","title":"Recorded show","status":"completed"}""" },
    )

    private val pages: List<Triple<String, String, @Composable (KobblerApi, FormFactor) -> Unit>> = listOf(
        Triple("channel", "SBS", { api, ff -> LiveChannelDetailScreen(channel = channel, entries = emptyList(), api = api, formFactor = ff) }),
        Triple("recording", "Recorded show", { api, ff ->
            LiveRecordingDetailScreen(recording = runBlocking { api.getLiveRecording("r1") }, api = api, formFactor = ff)
        }),
    )

    @Test
    fun `a live page has the app's back on phone and desktop and none on a tv`() = StubServer(routes).use { stub ->
        val api = KobblerApi(HttpClient(), stub.url, "token")
        for ((name, title, page) in pages) {
            for ((formFactor, backs) in listOf(FormFactor.TV to 0, FormFactor.Desktop to 1, FormFactor.Phone to 1)) {
                runUiTest(width = if (formFactor == FormFactor.Phone) 412 else 1280, height = 800) {
                    setContent { KobappTheme(formFactor = formFactor) { FocusScopeHost(id = name) { page(api, formFactor) } } }
                    waitForIdle()
                    onNodeWithText(title).assertExists()
                    assertEquals(backs, onAllNodesWithContentDescription("Back").fetchSemanticsNodes().size, "$name on $formFactor")
                }
            }
        }
    }
}
