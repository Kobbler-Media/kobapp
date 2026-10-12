package dev.kobbler.app.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.kobplayer.TrackInfo
import dev.kobbler.kobplayer.ui.OverlayChapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A remote opens the player's menus as a row it walks sideways, and OK picks. */
@OptIn(ExperimentalTestApi::class)
class PlayerTvPickerTest {

    private val chapters = listOf(
        OverlayChapter("Opening", 0L),
        OverlayChapter("Middle", 120_000L),
        OverlayChapter("Ending", 480_000L),
    )
    private val english = TrackInfo("en", "English")
    private val french = TrackInfo("fr", "French")
    private val commentary = TrackInfo("c", "Commentary")

    private class Rig {
        lateinit var focus: FocusManager
        val seeks = mutableListOf<Long>()
        var backs = 0
        var subtitle by mutableStateOf<TrackInfo?>(null)
        var audio by mutableStateOf<TrackInfo?>(null)
    }

    private fun tvPlayer(test: ComposeUiTest.(Rig) -> Unit) = runDesktopComposeUiTest(width = 1920, height = 1080) {
        val rig = Rig().apply { audio = english }
        setContent {
            rig.focus = LocalFocusManager.current
            KobappTheme(formFactor = FormFactor.TV) {
                PlayerOverlay(
                    title = "Film",
                    isPlaying = false,
                    currentMs = { 0L },
                    durationMs = 600_000L,
                    onPlayPause = {},
                    onSeek = { rig.seeks += it },
                    onBack = { rig.backs++ },
                    chapters = chapters,
                    chapterThumbnail = { ms -> Box(Modifier.size(160.dp, 90.dp).semantics { contentDescription = "frame@$ms" }) },
                    subtitleTracks = listOf(english, french),
                    selectedSubtitleTrack = rig.subtitle,
                    onSelectSubtitleTrack = { rig.subtitle = it },
                    audioTracks = listOf(english, commentary),
                    selectedAudioTrack = rig.audio,
                    onSelectAudioTrack = { rig.audio = it },
                )
            }
        }
        waitForIdle()
        test(rig)
    }

    private fun ComposeUiTest.press(key: Key) {
        onNode(isFocused()).performKeyInput { pressKey(key) }
        waitForIdle()
    }

    private fun ComposeUiTest.open(menu: String) {
        onNodeWithContentDescription(menu).requestFocus()
        press(Key.Enter)
    }

    private fun ComposeUiTest.focusedBounds() = onNode(isFocused()).fetchSemanticsNode().boundsInRoot

    private fun ComposeUiTest.assertFocusedOn(text: String) {
        val focused = onNode(isFocused(), useUnmergedTree = false).fetchSemanticsNode()
        val texts = focused.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }.map { it.text }
        assertTrue(texts.any { text in it }, "focus is on $texts, not '$text'")
    }

    private fun ComposeUiTest.walkRight(rig: Rig, from: String, to: String) {
        assertFocusedOn(from)
        val before = focusedBounds()
        runOnIdle { rig.focus.moveFocus(FocusDirection.Right) }
        waitForIdle()
        assertFocusedOn(to)
        val after = focusedBounds()
        assertTrue(after.left > before.left && after.top == before.top, "not a row: $before then $after")
    }

    @Test
    fun `chapters open as a row of frames and ok seeks`() = tvPlayer { rig ->
        open("Chapters")
        for (chapter in chapters) {
            assertEquals(1, onAllNodesWithContentDescription("frame@${chapter.startMs}").fetchSemanticsNodes().size, "${chapter.title} has no frame")
        }
        walkRight(rig, from = "Opening", to = "Middle")
        press(Key.Enter)
        assertEquals(listOf(120_000L), rig.seeks)
        assertTrue(onAllNodesWithText("Ending").fetchSemanticsNodes().isEmpty(), "the row stayed open")
    }

    @Test
    fun `audio opens as a row and ok picks a track`() = tvPlayer { rig ->
        open("Audio")
        walkRight(rig, from = "English", to = "Commentary")
        press(Key.Enter)
        assertEquals(commentary, rig.audio)
    }

    @Test
    fun `back closes an open row without leaving the player`() = tvPlayer { rig ->
        for (menu in listOf("Chapters", "Subtitles", "Audio")) {
            open(menu)
            press(Key.Back)
            assertEquals(0, rig.backs, "back on the $menu row left the player")
            assertTrue(onAllNodesWithText("French").fetchSemanticsNodes().isEmpty() && onAllNodesWithText("Middle").fetchSemanticsNodes().isEmpty() && onAllNodesWithText("Commentary").fetchSemanticsNodes().isEmpty(), "the $menu row stayed open")
        }
    }
}
