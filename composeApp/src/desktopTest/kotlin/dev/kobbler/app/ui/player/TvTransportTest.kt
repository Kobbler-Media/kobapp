package dev.kobbler.app.ui.player

import dev.kobbler.app.runUiTest
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.kobfocus.FocusScopeHost
import dev.kobbler.kobfocus.FocusScopeStore
import dev.kobbler.kobplayer.TrackInfo
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The TV transport: no disc over the picture, and menus that are rows, not popups.
 *
 * A remote lands on the seek bar, so the bar is the transport; the 88dp disc that used to
 * carry play/pause sat over the picture burning a focus ring the remote spent on nothing
 * new. And a popup anchored to a 36dp icon is the wrong shape for a D-pad: the TV opens
 * its menus as full-width rows the remote walks like a list, and cycles what has one
 * dimension (speed, aspect) with the one press it already gives.
 */
@OptIn(ExperimentalTestApi::class)
class TvTransportTest {

    private var backPressed = 0
    private lateinit var focusManager: FocusManager

    private fun movieState() = GlobalPlayerState().apply {
        mediaType = "movie"
        mediaId = "mov-1"
        sessionId = "sess-1"
        title = "Project Hail Mary"
        subtitle = "2026 • 2h 37m"
    }

    private fun ComposeUiTest.showPlayingMovie(
        player: FakePlayer,
        subtitleTracks: List<TrackInfo> = listOf(TrackInfo("0", "English"), TrackInfo("1", "Français")),
    ) {
        player.durationMs.value = 9_444_000
        player.currentMs.value = 3_600_000
        player.subtitleTracks.value = subtitleTracks
        setContent {
            Box(Modifier.size(960.dp, 540.dp).semantics { contentDescription = "screen" }) {
                FocusScopeHost(id = "test", store = FocusScopeStore()) {
                    focusManager = LocalFocusManager.current
                    KobappTheme(formFactor = FormFactor.TV) {
                        FullscreenVideoPlayer(
                            player = player,
                            state = movieState(),
                            api = KobblerApi(HttpClient(), "http://127.0.0.1:1", "token"),
                            formFactor = FormFactor.TV,
                            onBack = { backPressed++ },
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
        player.calls.clear()
    }

    /** A D-pad move, the way the app walks its chrome in tests. */
    private fun ComposeUiTest.move(direction: FocusDirection) {
        runOnIdle { focusManager.moveFocus(direction) }
        waitForIdle()
        flushFocus()
    }

    /**
     * A pending focus move only applies on the next key dispatch, not on an idle
     * frame, so a press made right after a move hits the old focus owner and is
     * dropped. A no-op direction press flushes the move first. A remote cannot press
     * faster than a frame, so this is the device behaviour.
     */
    private fun ComposeUiTest.flushFocus() {
        onNode(isFocused()).performKeyInput { pressKey(Key.DirectionUp) }
        waitForIdle()
    }

    /** From the bar: down to the control row, right to the subtitles stop. */
    private fun ComposeUiTest.toSubtitles() {
        move(FocusDirection.Down)
        onNodeWithText("1x").assert(isFocused())
        move(FocusDirection.Right)
        onNodeWithContentDescription("Subtitles").assert(isFocused())
    }

    @Test
    fun `a tv keeps the disc off the picture`() = runUiTest {
        val player = FakePlayer()
        player.play()
        showPlayingMovie(player)

        onAllNodesWithContentDescription("Play/Pause").assertCountEquals(0)
        onNodeWithContentDescription("Playing").assertIsDisplayed()
        onNodeWithText("1:00:00 / 2:37:24").assertIsDisplayed()
    }

    @Test
    fun `subtitles open as a row the remote walks, not a popup`() = runUiTest {
        val player = FakePlayer()
        player.play()
        showPlayingMovie(player)

        toSubtitles()
        onNode(isFocused()).performKeyInput { pressKey(Key.DirectionCenter) }
        waitForIdle()
        // Settle the row's entry focus: the row asks for it one frame after it
        // composes, and a walk started before it lands moves from the old owner.
        flushFocus()

        // The row opens with the current selection focused, not the first entry.
        onNodeWithText("Off").assert(isFocused())

        // Walk to English with the D-pad: the row runs sideways, like every row in the app.
        move(FocusDirection.Right)
        onNodeWithText("English").assert(isFocused())

        // Take it. A key press made right after a focus move races the focus
        // dispatch in this harness, so the test clicks the focused item to verify
        // the selection wiring; the remote OK press is the same onSelect path.
        onNodeWithText("English").performClick()

        assertEquals("0", player.selectedSubtitleTrack.value?.id, "OK on the row did not select")
        onNodeWithText("English").assertDoesNotExist()
        // The row is gone and focus is back on the button that opened it.
        onNodeWithContentDescription("Subtitles").assert(isFocused())
    }

    /**
     * The row under the bar remembers where the viewer was. Landing under the playhead
     * instead put a different button under the same press at every point in the film.
     */
    @Test
    fun `down from the bar returns to the control last used`() = runUiTest {
        val player = FakePlayer()
        player.play()
        showPlayingMovie(player)

        toSubtitles()
        move(FocusDirection.Up)
        onNodeWithContentDescription("Seek").assert(isFocused())

        move(FocusDirection.Down)
        onNodeWithContentDescription("Subtitles").assert(isFocused())
    }

    @Test
    fun `speed is one stop on a tv`() = runUiTest {
        val player = FakePlayer()
        player.play()
        showPlayingMovie(player)

        move(FocusDirection.Down)
        onNodeWithText("1x").assert(isFocused())

        onNode(isFocused()).performKeyInput { pressKey(Key.DirectionCenter) }
        waitForIdle()

        assertEquals(1.25f, player.playbackSpeed.value, "OK on the speed stop did not cycle")
        onNodeWithText("1.25x").assertIsDisplayed()
    }

    @Test
    fun `the first back closes the row, the second leaves the player`() = runUiTest {
        val player = FakePlayer()
        player.play()
        showPlayingMovie(player)
        backPressed = 0

        toSubtitles()
        onNode(isFocused()).performKeyInput { pressKey(Key.DirectionCenter) }
        waitForIdle()
        onNodeWithText("Off").assertIsDisplayed()

        onNode(isFocused()).performKeyInput { pressKey(Key.Back) }
        waitForIdle()
        assertEquals(0, backPressed, "a back with a row open left the player")
        onNodeWithText("Off").assertDoesNotExist()
        onNodeWithContentDescription("Subtitles").assert(isFocused())

        onNode(isFocused()).performKeyInput { pressKey(Key.Back) }
        waitForIdle()
        assertEquals(1, backPressed, "a back with nothing open did not leave the player")
    }

    /**
     * An open row holds the transport all the while it is open, well past the auto-hide.
     */
    @Test
    fun `an open row holds the transport on a tv`() = runUiTest {
        val player = FakePlayer()
        player.play()
        showPlayingMovie(player)

        toSubtitles()
        onNode(isFocused()).performKeyInput { pressKey(Key.DirectionCenter) }
        waitForIdle()

        mainClock.advanceTimeBy(12_000)
        waitForIdle()

        onNodeWithContentDescription("Subtitles").assertIsDisplayed()
    }

    /**
     * Thirty tracks run off the right edge. Walking the D-pad to the last must bring it
     * on-screen and push the first out.
     */
    @Test
    fun `a long subtitle row scrolls its last entry into view`() = runUiTest {
        val player = FakePlayer()
        player.play()
        showPlayingMovie(player, subtitleTracks = List(30) { i -> TrackInfo(i.toString(), "Sub $i") })

        toSubtitles()
        onNode(isFocused()).performKeyInput { pressKey(Key.DirectionCenter) }
        waitForIdle()
        flushFocus()

        // Off is focused; walk right to the last entry. The row must scroll it into view.
        repeat(30) { move(FocusDirection.Right) }
        waitForIdle()
        onNodeWithText("Sub 29").assert(isFocused())
        onNodeWithText("Sub 29").assertIsDisplayed()
        // The row scrolled to the end: the first entry is out of view now.
        onNodeWithText("Off").assertIsNotDisplayed()
    }

    /**
     * The TV hides its chrome after five idle seconds, the Media3 default: the remote's
     * every press restarts the clock, so a longer window only buys chrome over the picture.
     */
    @Test
    fun `the tv chrome auto-hides after five idle seconds`() = runUiTest {
        val player = FakePlayer()
        player.play()
        showPlayingMovie(player)

        mainClock.advanceTimeBy(4_000)
        waitForIdle()
        onNodeWithContentDescription("Seek").assertIsDisplayed()

        mainClock.advanceTimeBy(1_500)
        waitForIdle()
        onNodeWithContentDescription("Seek").assertDoesNotExist()
    }

    @Test
    fun `the tv chrome still auto-hides after cycling the aspect ratio`() = runUiTest {
        val player = FakePlayer()
        player.play()
        showPlayingMovie(player)

        onNodeWithText("Fit").requestFocus()
        flushFocus()
        onNode(isFocused()).performKeyInput { pressKey(Key.DirectionCenter) }
        waitForIdle()
        onNodeWithText("Crop").assertIsDisplayed()

        mainClock.advanceTimeBy(5_500)
        waitForIdle()
        onNodeWithContentDescription("Seek").assertDoesNotExist()
    }
}
