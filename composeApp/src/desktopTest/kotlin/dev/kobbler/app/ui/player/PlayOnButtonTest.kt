package dev.kobbler.app.ui.player

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import dev.kobbler.app.UiTimeoutMs
import dev.kobbler.app.platform.CastRouter
import dev.kobbler.app.platform.PlaybackProtocol
import dev.kobbler.app.runUiTest
import dev.kobbler.app.theme.KobappTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * One button for every way this device can hand playback over, shown only when there is
 * somewhere to hand it to — or a hand-over already running that has to be reachable.
 */
@OptIn(ExperimentalTestApi::class)
class PlayOnButtonTest {

    private data class Row(val name: String, val setup: (FakeBackend) -> Unit, val shown: Boolean)

    @Test
    fun `the button shows only when there is a target or a session`() {
        listOf(
            Row("no targets", {}, shown = false),
            Row("a target", { it.found("Projector TV") }, shown = true),
            Row("connecting, nothing listed", { it.sender.connecting("Projector TV") }, shown = true),
            Row("connected, target gone", { it.found("Projector TV"); it.sender.connect("Projector TV"); it.targets.value = emptyList() }, shown = true),
        ).forEach { row ->
            runUiTest {
                val backend = FakeBackend(PlaybackProtocol.GoogleCast)
                row.setup(backend)
                setContent {
                    CompositionLocalProvider(LocalCastRouter provides CastRouter(listOf(backend))) {
                        KobappTheme { PlayOnButton() }
                    }
                }
                waitForIdle()
                val found = onAllNodesWithContentDescription("Play on").fetchSemanticsNodes().size
                assertEquals(if (row.shown) 1 else 0, found, row.name)
            }
        }
    }

    private fun row(name: String, protocol: String) = hasText(name) and hasText(protocol) and hasClickAction()

    @Test
    fun `the picker lists every protocol's targets and hands the pick to its own backend`() = runUiTest {
        val cast = FakeBackend(PlaybackProtocol.GoogleCast).apply { found("Projector TV") }
        val dlna = FakeBackend(PlaybackProtocol.Dlna).apply { found("Projector TV", "Bedroom TV") }
        setContent {
            CompositionLocalProvider(LocalCastRouter provides CastRouter(listOf(cast, dlna))) {
                KobappTheme { PlayOnButton() }
            }
        }
        onNodeWithContentDescription("Play on").performClick()
        waitUntil(timeoutMillis = UiTimeoutMs) { cast.scanning && dlna.scanning }

        onNode(row("Projector TV", "Chromecast")).assertExists()
        onNode(row("Projector TV", "DLNA")).assertExists()
        onNode(row("Bedroom TV", "DLNA")).performClick()

        waitUntil(timeoutMillis = UiTimeoutMs) { dlna.connected.isNotEmpty() }
        assertEquals(listOf("Bedroom TV"), dlna.connected.map { it.name })
        assertEquals(emptyList(), cast.connected.toList())
    }
}
