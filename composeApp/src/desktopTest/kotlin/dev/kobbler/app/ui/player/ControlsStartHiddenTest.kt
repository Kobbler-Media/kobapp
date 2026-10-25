package dev.kobbler.app.ui.player

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import dev.kobbler.app.runUiTest
import dev.kobbler.kobplayer.ui.PlayerChrome
import dev.kobbler.kobplayer.ui.VideoPlayerOverlay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

/** The picture opens clear; the controls come up only when the viewer reaches for them. */
@OptIn(ExperimentalTestApi::class)
class ControlsStartHiddenTest {

    @Test
    fun `controls stay hidden until the viewer reaches for them`() {
        val failures = PlayerChrome.entries.mapNotNull { chrome ->
            runCatching { check(chrome) }.exceptionOrNull()?.let { "$chrome: ${it.message?.lineSequence()?.first()}" }
        }
        assertEquals(emptyList(), failures)
    }

    private fun check(chrome: PlayerChrome) = runUiTest {
        setContent {
            VideoPlayerOverlay(
                title = "A Movie",
                isPlaying = true,
                position = MutableStateFlow(60_000L),
                durationMs = 7_680_000L,
                onPlayPause = {},
                onSeek = {},
                onBack = {},
                chrome = chrome,
            )
        }
        waitForIdle()
        check(!titleShown()) { "controls shown on open" }
        revealControls(chrome)
        check(titleShown()) { "controls did not come up" }
    }

    private fun ComposeUiTest.titleShown() = onAllNodesWithText("A Movie").fetchSemanticsNodes().isNotEmpty()
}
