package dev.kobbler.app.ui.player

import dev.kobbler.app.runUiTest
import dev.kobbler.app.UiTimeoutMs
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.DataCache
import dev.kobbler.kobfocus.FocusScopeHost
import dev.kobbler.kobfocus.LocalFocusScope
import dev.kobbler.kobfocus.focusKey
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertTrue

/** Opening the channel list while watching lands on the channel being watched. */
@OptIn(ExperimentalTestApi::class)
class LiveChannelRailFocusTest {

    private fun guideNow(favourites: Set<Int>) = buildString {
        append("""{"items":[""")
        append((1..60).joinToString(",") {
            val fav = it in favourites
            """{"channel":{"id":"c$it","lcn":$it,"name":"Channel $it","favourite":$fav}}"""
        })
        append("]}")
    }

    private fun opensOn(current: Int, favourites: Set<Int>) = runUiTest(width = 960, height = 540) {
        DataCache.clear()
        StubServer(mapOf("/api/v1/admin/livetv/guide/now" to { _ -> guideNow(favourites) })).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            var railOpen by mutableStateOf(false)
            setContent {
                KobappTheme(formFactor = FormFactor.TV) {
                    FocusScopeHost(id = "player") {
                        val scope = LocalFocusScope.current
                        Row {
                            Box(Modifier.size(40.dp).focusKey("transport.play", preferredEntry = true).focusable())
                            if (railOpen) {
                                LiveChannelRail(
                                    api = api,
                                    currentChannelId = "c$current",
                                    accent = Color.Red,
                                    formFactor = FormFactor.TV,
                                    tunersInUse = 1,
                                    tunerCount = 4,
                                    onPick = {},
                                    onOpenGuide = null,
                                    onDismiss = {},
                                )
                            }
                        }
                        LaunchedEffect(railOpen) { if (railOpen) scope.focusFirst() }
                    }
                }
            }
            waitForIdle()
            railOpen = true
            mainClock.advanceTimeBy(2_000)
            waitUntil(timeoutMillis = UiTimeoutMs) {
                onAllNodes(isFocused() and hasText("Channel $current", substring = true)).fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue(onAllNodes(isFocused() and hasText("Channel $current", substring = true)).fetchSemanticsNodes().isNotEmpty())
        }
        DataCache.clear()
    }

    @Test
    fun `a favourite far down the list`() = opensOn(current = 45, favourites = (1..60).toSet())

    @Test
    fun `a channel that is not a favourite`() = opensOn(current = 45, favourites = setOf(2, 3, 4))
}
