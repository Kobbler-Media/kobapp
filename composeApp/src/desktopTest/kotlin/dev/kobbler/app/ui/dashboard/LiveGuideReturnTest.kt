package dev.kobbler.app.ui.dashboard

import dev.kobbler.app.runUiTest
import dev.kobbler.app.UiTimeoutMs
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.UserSettings
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.DataCache
import dev.kobbler.app.ui.dashboard.livetv.LastLiveChannelKey
import dev.kobbler.app.ui.player.GlobalPlayerState
import dev.kobbler.app.ui.player.StubServer
import dev.kobbler.kobfocus.FocusScopeHost
import io.ktor.client.HttpClient
import kotlin.test.Test
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import dev.kobbler.app.data.AuthState
import dev.kobbler.app.data.LocalAuthState
import dev.kobbler.app.ui.navigation.DesktopScaffold
import dev.kobbler.app.ui.navigation.Destination
import dev.kobbler.app.ui.navigation.NavigationState
import dev.kobbler.app.ui.navigation.defaultNavConfig
import dev.kobbler.kobfocus.FocusScopeStore
import dev.kobbler.kobfocus.LocalFocusScopeStore
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/** Closing the player puts the guide back on the channel that was being watched. */
@OptIn(ExperimentalTestApi::class)
class LiveGuideReturnTest {

    private fun guide(): String {
        val now = Clock.System.now()
        val start = now - 10.minutes
        val end = now + 50.minutes
        return buildString {
            append("""{"channels":[""")
            append((1..5).joinToString(",") {
                """{"channel":{"id":"c$it","lcn":$it,"name":"Channel $it"},"entries":[""" +
                    """{"id":"g$it","channel_id":"c$it","start_time":"$start","end_time":"$end","title":"Programme $it","synopsis":"Synopsis $it"}]}"""
            })
            append("]}")
        }
    }

    private fun guideScreen(last: String, test: androidx.compose.ui.test.DesktopComposeUiTest.(GlobalPlayerState) -> Unit) =
        runUiTest(width = 1280, height = 720) {
            DataCache.clear()
            UserSettings.putString(LastLiveChannelKey, last)
            StubServer(
                mapOf(
                    "/api/v1/admin/livetv/tuners" to { _ -> """{"items":[]}""" },
                    "/api/v1/admin/livetv/channels" to { _ -> """{"items":[],"total":5}""" },
                    "/api/v1/admin/livetv/guide" to { _ -> guide() },
                ),
            ).use { stub ->
                val gps = GlobalPlayerState()
                setContent {
                    KobappTheme(formFactor = FormFactor.TV) {
                        FocusScopeHost(id = "livetv") {
                            LiveTvScreen(api = KobblerApi(HttpClient(), stub.url, "token"), formFactor = FormFactor.TV, gps = gps)
                        }
                    }
                }
                test(gps)
            }
        }

    /** Entered from the TV rail, which is how the remote gets there: the first cell must not win. */
    @Test
    fun `the guide opens on the channel watched last`() = runUiTest(width = 1280, height = 720) {
        DataCache.clear()
        StubServer(
            mapOf(
                "/api/v1/admin/livetv/tuners" to { _ -> """{"items":[]}""" },
                "/api/v1/admin/livetv/channels" to { _ -> """{"items":[],"total":5}""" },
                "/api/v1/admin/livetv/guide" to { _ -> guide() },
                "/api/v1/admin/discover" to { _ -> """{"watch_next":[{"media_type":"movie","media_id":"m1","title":"Watching","link":"/movies/m1","progress_percent":40}],"recently_added":[],"sections":[]}""" },
                "/api/v1/admin/dashboard" to { _ -> "{}" },
            ),
        ).use { stub ->
            val nav = NavigationState()
            setContent {
                val auth = AuthState(rememberCoroutineScope(), FormFactor.TV)
                CompositionLocalProvider(LocalAuthState provides auth, LocalFocusScopeStore provides FocusScopeStore()) {
                    KobappTheme(formFactor = FormFactor.TV) {
                        DesktopScaffold(
                            nav = nav,
                            navConfig = defaultNavConfig,
                            api = KobblerApi(HttpClient(), stub.url, "token"),
                            formFactor = FormFactor.TV,
                            player = null,
                            gps = GlobalPlayerState(),
                        )
                    }
                }
            }
            waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithText("Watching").fetchSemanticsNodes().isNotEmpty() }
            mainClock.advanceTimeBy(1_000)
            UserSettings.putString(LastLiveChannelKey, "c4")
            val steps = defaultNavConfig.sidebarTabs.indexOf(Destination.Tab.LiveTv)
            (listOf(Key.Back) + List(steps) { Key.DirectionDown } + Key.DirectionRight).forEach { key ->
                onRoot().performKeyInput { pressKey(key) }
                mainClock.advanceTimeBy(400)
                waitForIdle()
            }
            waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithText("Programme 1").fetchSemanticsNodes().isNotEmpty() }
            mainClock.advanceTimeBy(1_000)
            waitForIdle()
            assertTrue(focusedOn("Channel 4"), "focus is not on channel 4")
        }
    }

    @Test
    fun `the guide reselects the channel watched last`() = guideScreen(last = "c1") { gps ->
        waitUntil(timeoutMillis = UiTimeoutMs) { focusedOn("Channel 1") }

        gps.isFullscreen = true
        waitForIdle()
        UserSettings.putString(LastLiveChannelKey, "c4")
        gps.isFullscreen = false

        waitUntil(timeoutMillis = UiTimeoutMs) { focusedOn("Channel 4") }
    }

    private fun androidx.compose.ui.test.DesktopComposeUiTest.focusedOn(name: String) =
        onAllNodes(isFocused() and hasText(name, substring = true)).fetchSemanticsNodes().isNotEmpty()
}
