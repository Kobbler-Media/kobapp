package dev.kobbler.app.ui.player

import dev.kobbler.app.platform.FormFactor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Two ways out of a fullscreen player — the remote's BACK and the overlay's own arrow —
 * had grown two different answers, so playback was torn down by one and left running by
 * the other.
 */
class FullscreenExitTest {

    private fun state(type: String) = GlobalPlayerState().apply {
        mediaType = type
        mediaId = "m-1"
    }

    /** A guide with no player in it is where BACK lands, so the tuner goes with it. */
    @Test
    fun `leaving a live channel on a tv stops it`() {
        assertEquals(
            FullscreenExit.Stop,
            state("livetv").fullscreenExit(FormFactor.TV, isPlaying = true),
        )
    }

    /**
     * The mini player's controls are tap-only and it carries no focus target, so on a TV
     * it is somewhere a remote can never reach playback again.
     */
    @Test
    fun `a film on a tv stops rather than shrinking`() {
        assertEquals(
            FullscreenExit.Stop,
            state("movie").fullscreenExit(FormFactor.TV, isPlaying = true),
        )
    }

    @Test
    fun `a film playing on a phone shrinks into the mini player`() {
        assertEquals(
            FullscreenExit.ToMiniPlayer,
            state("movie").fullscreenExit(FormFactor.Phone, isPlaying = true),
        )
    }

    /** Paused is a viewer who has stopped watching, not one who is multitasking. */
    @Test
    fun `a paused film on a phone stops`() {
        assertEquals(
            FullscreenExit.Stop,
            state("movie").fullscreenExit(FormFactor.Phone, isPlaying = false),
        )
    }
}
