package dev.kobbler.app.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.kobfocus.FocusScopeHost
import dev.kobbler.kobfocus.FocusScopeStore
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bar answers the middle button.
 *
 * A TV opens a tune on the seek bar, and OK there was dropped: the bar consumed LEFT/RIGHT
 * only, and the overlay's own Enter handler runs just while the surface itself holds focus —
 * which a bar that takes entry focus no longer does. The key a viewer presses most was the
 * one key nothing listened for, so pausing meant aiming at a disc of chrome in the middle of
 * the picture and hunting for it with the D-pad first.
 */
@OptIn(ExperimentalTestApi::class)
class SeekActivateTest {

    private fun movieState() = GlobalPlayerState().apply {
        mediaType = "movie"
        mediaId = "mov-1"
        sessionId = "sess-1"
        title = "Project Hail Mary"
        subtitle = "2026 • 2h 37m"
    }

    /** A film mid-play, with the transport up and entry focus settled where a remote lands. */
    private fun ComposeUiTest.showPlayingMovie(player: FakePlayer) {
        player.durationMs.value = 9_444_000
        player.currentMs.value = 3_600_000
        setContent {
            Box(Modifier.size(960.dp, 540.dp).semantics { contentDescription = "screen" }) {
                FocusScopeHost(id = "test", store = FocusScopeStore()) {
                    KobappTheme(formFactor = FormFactor.TV) {
                        FullscreenVideoPlayer(
                            player = player,
                            state = movieState(),
                            api = KobblerApi(HttpClient(), "http://127.0.0.1:1", "token"),
                            formFactor = FormFactor.TV,
                            onBack = {},
                            isFullscreen = true,
                            videoSurface = { },
                            onOpenGuide = {},
                        )
                    }
                }
            }
        }
        waitForIdle()
        mainClock.advanceTimeBy(500)
        waitForIdle()
        // Whatever the opening itself asked the player for is not the viewer's doing.
        player.calls.clear()
    }

    @Test
    fun `a tv opens a film on the seek bar`() = runComposeUiTest {
        val player = FakePlayer()
        showPlayingMovie(player)
        onNodeWithContentDescription("Seek").assert(isFocused())
    }

    @Test
    fun `ok on the seek bar pauses what is playing`() = runComposeUiTest {
        val player = FakePlayer()
        player.play()
        showPlayingMovie(player)

        onNode(isFocused()).performKeyInput { pressKey(Key.DirectionCenter) }
        waitForIdle()

        assertTrue(player.calls.contains("pause"), "OK on the bar left playback running: ${player.calls}")
    }

    @Test
    fun `ok on the seek bar resumes what is paused`() = runComposeUiTest {
        val player = FakePlayer()
        player.play()
        player.pause()
        showPlayingMovie(player)

        onNode(isFocused()).performKeyInput { pressKey(Key.DirectionCenter) }
        waitForIdle()

        assertTrue(player.calls.contains("play"), "OK on the bar left the tune paused: ${player.calls}")
    }

    /**
     * A keyboard is the other remote. ENTER and SPACE reach the bar the same way, and the key
     * that works everywhere else in this transport has to work on the bar too.
     */
    @Test
    fun `enter and space on the seek bar reach the same transport as ok`() = runComposeUiTest {
        for (key in listOf(Key.Enter, Key.Spacebar)) {
            val player = FakePlayer()
            player.play()
            showPlayingMovie(player)

            onNode(isFocused()).performKeyInput { pressKey(key) }
            waitForIdle()

            assertTrue(player.calls.contains("pause"), "$key on the bar left playback running: ${player.calls}")
        }
    }

    /** LEFT and RIGHT stay the viewer's, whatever else the bar now answers. */
    @Test
    fun `the middle button does not spend the step keys`() = runComposeUiTest {
        val player = FakePlayer()
        player.play()
        showPlayingMovie(player)
        val from = player.currentMs.value

        onNode(isFocused()).performKeyInput {
            pressKey(Key.DirectionRight)
            pressKey(Key.DirectionRight)
        }
        mainClock.advanceTimeBy(600)
        waitForIdle()

        assertEquals(0, player.calls.count { it == "pause" || it == "play" }, "${player.calls}")
        val seek = player.calls.filter { it.startsWith("seek:") }.lastOrNull()
            ?.substringAfter(":")?.toLongOrNull()
        assertEquals(from + 20_000, seek, "two RIGHT steps: ${player.calls}")
    }
}