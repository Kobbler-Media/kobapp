package dev.kobbler.app.ui.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import dev.kobbler.app.runUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Leaving playback undoes only the fullscreen that playback itself asked for. */
@OptIn(ExperimentalTestApi::class)
class PlaybackFullscreenSyncTest {

    private fun windowAfterPlayback(startFullscreen: Boolean): List<Boolean> {
        val seen = mutableListOf<Boolean>()
        runUiTest {
            var playing by mutableStateOf(false)
            var window by mutableStateOf(startFullscreen)
            setContent {
                SyncPlaybackFullscreen(playing, window) { window = !window }
            }
            waitForIdle()
            seen += window
            playing = true
            waitForIdle()
            seen += window
            playing = false
            waitForIdle()
            seen += window
        }
        return seen
    }

    @Test
    fun `a window already fullscreen stays fullscreen after playback`() {
        assertEquals(listOf(true, true, true), windowAfterPlayback(startFullscreen = true))
    }

    @Test
    fun `a windowed window returns to windowed after playback`() {
        assertEquals(listOf(false, true, false), windowAfterPlayback(startFullscreen = false))
    }
}
