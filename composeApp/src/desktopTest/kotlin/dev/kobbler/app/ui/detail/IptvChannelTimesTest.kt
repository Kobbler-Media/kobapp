package dev.kobbler.app.ui.detail

import dev.kobbler.app.runUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import dev.kobbler.app.data.IptvChannel
import dev.kobbler.app.data.IptvEpgEntry
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.player.GlobalPlayerState
import dev.kobbler.kobfocus.FocusScopeHost
import io.ktor.client.HttpClient
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** The feed's times are UTC; the schedule is read in the viewer's own zone, as the web does. */
@OptIn(ExperimentalTestApi::class)
class IptvChannelTimesTest {

    @Test
    fun `the schedule is in local time, not the feed's UTC`() {
        val saved = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Australia/Melbourne"))
        try {
            val now = Clock.System.now()
            val later = Instant.fromEpochSeconds((now + 3.hours).epochSeconds / 3600 * 3600)
            fun entry(id: String, title: String, start: Instant, end: Instant) =
                IptvEpgEntry(id, title, null, start.toString(), end.toString())
            runUiTest(width = 1280, height = 900) {
                setContent {
                    KobappTheme(formFactor = FormFactor.Desktop) {
                        FocusScopeHost(id = "iptv-channel") {
                            IptvChannelDetailScreen(
                                channel = IptvChannel(id = "i1", name = "Sky Sport", iconUrl = null, category = null, language = null, country = null),
                                epg = listOf(
                                    entry("e1", "Derby Day", now - 10.minutes, later),
                                    entry("e2", "Late Show", later, later + 1.hours),
                                ),
                                api = KobblerApi(HttpClient(), "http://127.0.0.1:9", "token"),
                                player = null,
                                gps = GlobalPlayerState(),
                                onBack = {},
                            )
                        }
                    }
                }
                waitForIdle()
                val melbourne = java.time.Instant.ofEpochSecond(later.epochSeconds).atZone(java.time.ZoneId.of("Australia/Melbourne"))
                val local = "%02d:%02d".format(melbourne.hour, melbourne.minute)
                val utc = later.toString().substring(11, 16)
                val hasLocal = onAllNodesWithText(local, substring = true).fetchSemanticsNodes().isNotEmpty()
                val hasUtc = onAllNodesWithText(utc, substring = true).fetchSemanticsNodes().isNotEmpty()
                assertEquals(true to false, hasLocal to hasUtc, "expected $local (local), not $utc (UTC)")
            }
        } finally {
            TimeZone.setDefault(saved)
        }
    }
}
