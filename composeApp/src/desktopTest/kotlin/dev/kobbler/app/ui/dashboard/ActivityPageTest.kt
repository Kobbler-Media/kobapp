package dev.kobbler.app.ui.dashboard

import dev.kobbler.app.runUiTest
import dev.kobbler.app.UiTimeoutMs
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.kobbler.app.data.AuthState
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.LocalAuthState
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.DataCache
import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertTrue

/** One Activity page: what happened, and what the server wants the viewer to know. */
@OptIn(ExperimentalTestApi::class)
class ActivityPageTest {

    private val activity = """{"items":[{"id":"a1","event_kind":"watched","title":"Watched Slow Horses","created_at":"2026-10-22T10:00:00Z"}]}"""
    private val inbox = """{"notifications":[{"id":"n1","title":"Download finished","message":"Dune is ready","read":false}],"total":1}"""

    @Test
    fun `the page switches between activity and notifications`() = runUiTest(width = 1280, height = 800) {
        DataCache.clear()
        StubServer(
            mapOf(
                "/api/v1/library/activity" to { _ -> activity },
                "/api/v1/admin/notifications/inbox" to { _ -> inbox },
            ),
        ).use { stub ->
            setContent {
                val auth = AuthState(rememberCoroutineScope(), FormFactor.Desktop)
                CompositionLocalProvider(LocalAuthState provides auth) {
                    KobappTheme(formFactor = FormFactor.Desktop) {
                        ActivityPage(api = KobblerApi(HttpClient(), stub.url, "token"), formFactor = FormFactor.Desktop)
                    }
                }
            }
            waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithText("Watched Slow Horses").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Notifications", substring = true).performClick()
            waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithText("Dune is ready").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(onAllNodesWithText("Watched Slow Horses").fetchSemanticsNodes().isEmpty())
        }
    }
}
