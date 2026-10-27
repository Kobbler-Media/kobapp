package dev.kobbler.kobplayer

import dev.kobbler.kobplayer.ui.Branding
import dev.kobbler.kobplayer.ui.LogoLoad
import dev.kobbler.kobplayer.ui.branding
import dev.kobbler.kobplayer.ui.playbackHasBegun
import kotlin.test.assertEquals
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The title artwork belongs over a stream that has not started, and nowhere else.
 *
 * The overlay is composed fresh every time the player comes back from the background or
 * out of PiP, so a paused title has never reported playing to that instance. Judged on
 * that alone, the logo was drawn back over the transport of something twenty minutes in,
 * and the only way out was to press the play button underneath it.
 */
class BrandingOverlayTest {

    @Test
    fun `a stream that has not started yet is branded`() {
        assertFalse(playbackHasBegun(isPlaying = false, positionMs = 0))
    }

    @Test
    fun `a paused title part way through is not`() {
        assertTrue(
            playbackHasBegun(isPlaying = false, positionMs = 1_380_000),
            "the logo would be redrawn over a paused player's own controls",
        )
    }

    @Test
    fun `playing is enough on its own`() {
        assertTrue(playbackHasBegun(isPlaying = true, positionMs = 0))
    }

    @Test
    fun `the title only stands in when there is no logo to show`() {
        val cases = listOf(
            Triple("no logo", null, LogoLoad.Loading) to Branding.Title,
            Triple("loading", "logo.png", LogoLoad.Loading) to Branding.Nothing,
            Triple("loaded", "logo.png", LogoLoad.Loaded) to Branding.Logo,
            Triple("failed", "logo.png", LogoLoad.Failed) to Branding.Title,
        )
        cases.forEach { (case, expected) ->
            assertEquals(expected, branding(case.second, case.third), case.first)
        }
    }
}
