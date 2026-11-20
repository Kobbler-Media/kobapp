package dev.kobbler.app.ui.player

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.kobbler.app.platform.PlaybackProtocol
import dev.kobbler.app.platform.PlaybackTarget
import dev.kobbler.app.platform.PlaybackTargetKind
import dev.kobbler.app.runUiTest
import dev.kobbler.app.theme.KobappTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** The cast picker and controller are the app's own dialogs, wired to the session. */
@OptIn(ExperimentalTestApi::class)
class CastDialogsTest {

    private val routes = listOf(
        PlaybackTarget("tv", "Projector TV", PlaybackTargetKind.Tv, PlaybackProtocol.GoogleCast),
        PlaybackTarget("spk", "Ensuite speaker", PlaybackTargetKind.Speaker, PlaybackProtocol.GoogleCast),
        PlaybackTarget("all", "All speakers", PlaybackTargetKind.Group, PlaybackProtocol.GoogleCast),
    )

    @Test
    fun `the picker lists what it found and casts to the one picked`() = runUiTest {
        val picked = mutableListOf<String>()
        setContent { KobappTheme { CastPickerDialog(routes, onPick = { picked += it.id }, onDismiss = {}) } }
        onNodeWithText("Play on").assertExists()
        routes.forEach { onNodeWithText(it.name).assertExists() }
        onNodeWithText("Projector TV").performClick()
        assertEquals(listOf("tv"), picked)
    }

    @Test
    fun `the picker says it is still looking while nothing has turned up`() = runUiTest {
        setContent { KobappTheme { CastPickerDialog(emptyList(), onPick = {}, onDismiss = {}) } }
        onNodeWithText("Looking for devices…").assertExists()
    }

    private val playing = CastSessionDto(
        deviceName = "Projector TV", mediaId = "e1", mediaType = "episode",
        mediaTitle = "High Maintenance", subtitle = "S1 E1 • Meth(od)",
        positionMs = 260_000, durationMs = 319_000, isPlaying = true,
    )

    @Test
    fun `the controller shows what is playing and stops the cast`() = runUiTest {
        var toggles = 0
        var stops = 0
        val session = playing
        setContent {
            KobappTheme {
                CastControllerDialog(
                    deviceName = "Projector TV",
                    session = session,
                    posterUrl = null,
                    backdropUrl = null,
                    onPlayPause = { toggles++ },
                    onStop = { stops++ },
                    onDismiss = {},
                )
            }
        }
        onNodeWithText("Projector TV").assertExists()
        onNodeWithText("High Maintenance").assertExists()
        onNodeWithText("S1 E1 • Meth(od)").assertExists()
        onNodeWithContentDescription("Pause").performClick()
        onNodeWithText("Stop casting").performClick()
        assertEquals(1, toggles)
        assertEquals(1, stops)
    }

    @Test
    fun `the art keeps its own shape - a backdrop wide, a poster tall`() {
        listOf(
            Triple("data:poster", null, 2f / 3f),
            Triple("data:poster", "data:backdrop", 16f / 9f),
        ).forEach { (poster, backdrop, aspect) ->
            runUiTest {
                setContent {
                    KobappTheme {
                        CastControllerDialog("Projector TV", playing, posterUrl = poster, backdropUrl = backdrop, onPlayPause = {}, onStop = {}, onDismiss = {})
                    }
                }
                val box = onNodeWithTag(CastArtTag).fetchSemanticsNode().boundsInRoot
                assertEquals(aspect, box.width / box.height, 0.02f, "poster=$poster backdrop=$backdrop")
            }
        }
    }

    @Test
    fun `the full-screen cast player keeps the art's shape too`() {
        listOf(
            Triple("data:poster", null, 2f / 3f),
            Triple("data:poster", "data:backdrop", 16f / 9f),
        ).forEach { (poster, backdrop, aspect) ->
            runUiTest(width = 800, height = 1600) {
                setContent {
                    KobappTheme {
                        CastExpandedController(
                            session = playing, subtitle = playing.subtitle, posterUrl = poster, backdropUrl = backdrop,
                            onPlayPause = {}, onSeekTo = {}, onDisconnect = {}, onCollapse = {},
                        )
                    }
                }
                val box = onNodeWithTag(CastArtTag).fetchSemanticsNode().boundsInRoot
                assertEquals(aspect, box.width / box.height, 0.02f, "poster=$poster backdrop=$backdrop")
            }
        }
    }

    @Test
    fun `a session with nothing loaded yet still offers to stop`() = runUiTest {
        var stops = 0
        setContent {
            KobappTheme {
                CastControllerDialog("Projector TV", session = null, posterUrl = null, backdropUrl = null, onPlayPause = null, onStop = { stops++ }, onDismiss = {})
            }
        }
        onNodeWithText("Nothing playing yet").assertExists()
        onNodeWithText("Stop casting").performClick()
        assertEquals(1, stops)
    }
}
