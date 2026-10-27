package dev.kobbler.app.ui.player

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.runUiTest
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.DataCache
import dev.kobbler.kobfocus.FocusScopeHost
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertTrue

/** The rail opens already sitting on the watched channel — it never scrolls there in view. */
@OptIn(ExperimentalTestApi::class)
class LiveChannelRailSteadyTest {

    private val guideNow = buildString {
        append("""{"items":[""")
        append((1..60).joinToString(",") { """{"channel":{"id":"c$it","lcn":$it,"name":"Channel $it","favourite":true}}""" })
        append("]}")
    }

    @Test
    fun `reopening the rail shows the watched channel centred from its first frame`() = runUiTest(width = 960, height = 540) {
        DataCache.clear()
        StubServer(mapOf("/api/v1/admin/livetv/guide/now" to { _ -> guideNow })).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            var railOpen by mutableStateOf(true)
            setContent {
                KobappTheme(formFactor = FormFactor.TV) {
                    FocusScopeHost(id = "player") {
                        if (railOpen) {
                            LiveChannelRail(
                                api = api, currentChannelId = "c45", accent = Color.Red, formFactor = FormFactor.TV,
                                tunersInUse = 1, tunerCount = 4, onPick = {}, onOpenGuide = null, onDismiss = {},
                                modifier = Modifier.fillMaxHeight(),
                            )
                        }
                    }
                }
            }
            val watched = hasText("Channel 45", substring = true)
            waitUntil(timeoutMillis = 10_000) { onAllNodes(watched).fetchSemanticsNodes().isNotEmpty() }
            mainClock.advanceTimeBy(2_000)
            railOpen = false
            waitForIdle()

            mainClock.autoAdvance = false
            railOpen = true
            val tops = mutableListOf<Dp>()
            repeat(40) {
                mainClock.advanceTimeByFrame()
                onAllNodes(watched).fetchSemanticsNodes().firstOrNull()?.let {
                    tops += onAllNodes(watched)[0].getUnclippedBoundsInRoot().top
                }
            }
            mainClock.autoAdvance = true
            assertTrue(tops.isNotEmpty(), "the watched channel never appeared")
            assertTrue(tops.all { (it - tops.first()).value.let { d -> d > -1f && d < 1f } }, "the watched row moved after it appeared: $tops")
            val centre = tops.first() + 46.dp
            assertTrue((centre - 270.dp).value.let { d -> d > -80f && d < 80f }, "the watched row opened at ${tops.first()}, not mid-screen")
        }
        DataCache.clear()
    }
}
