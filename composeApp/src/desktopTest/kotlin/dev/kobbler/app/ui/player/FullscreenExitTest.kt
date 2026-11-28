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

    private data class Case(
        val name: String,
        val type: String,
        val formFactor: FormFactor,
        val isPlaying: Boolean,
        val miniPlayer: Boolean,
        val exit: FullscreenExit,
    )

    @Test
    fun `back shrinks into the mini player only where it was asked for and can be reached`() {
        listOf(
            Case("a live channel on a tv", "livetv", FormFactor.TV, true, true, FullscreenExit.Stop),
            // Its controls are tap-only with no focus target: a remote could never reach it.
            Case("a film on a tv", "movie", FormFactor.TV, true, true, FullscreenExit.Stop),
            Case("a film on a phone by default", "movie", FormFactor.Phone, true, false, FullscreenExit.Stop),
            Case("a film on a phone with it on", "movie", FormFactor.Phone, true, true, FullscreenExit.ToMiniPlayer),
            Case("a film on a desktop by default", "movie", FormFactor.Desktop, true, false, FullscreenExit.Stop),
            Case("a film on a desktop with it on", "movie", FormFactor.Desktop, true, true, FullscreenExit.ToMiniPlayer),
            Case("a paused film with it on", "movie", FormFactor.Phone, false, true, FullscreenExit.Stop),
        ).forEach {
            assertEquals(
                it.exit,
                state(it.type).fullscreenExit(it.formFactor, it.isPlaying, it.miniPlayer),
                it.name,
            )
        }
    }
}
