package dev.kobbler.kobplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Deciding, without a list of chip names, whether this device's decoder is the silent kind.
 *
 * A decoder that accepts field pictures and emits nothing is not an error as far as VLC is
 * concerned — no event, no error state, nothing to recover from — so the only evidence is
 * a clock that runs while no frame is ever presented. The danger is that sound ahead of
 * picture is *ordinary* on DVB-T: a tuner joining mid-GOP waits for the next IDR, and a
 * television's own tuner does it too. So the window has to sit well past an honest wait.
 */
class FieldDecodeTest {

    private fun probe(
        fieldCoded: Boolean = true,
        verdict: FieldDecodeVerdict = FieldDecodeVerdict.Unknown,
        presentedVideo: Boolean = false,
        playedMs: Long = FieldDecodeProbeMs,
    ) = fieldDecodeProbeFailed(fieldCoded, verdict, presentedVideo, playedMs)

    @Test
    fun `sound with no picture past the window is the silent decoder`() {
        assertTrue(probe())
    }

    /** The honest wait. Firing inside it turns a slow channel into a permanent downgrade. */
    @Test
    fun `sound with no picture inside the window is a channel still finding its keyframe`() {
        assertFalse(probe(playedMs = FieldDecodeProbeMs - 1))
        assertFalse(probe(playedMs = 3_000))
    }

    @Test
    fun `a window that would fire during an ordinary keyframe wait is not worth having`() {
        // A DVB-T GOP is about a second, and a television's own tuner is visibly slower
        // than that on a bad day. Ten seconds is far past anything a healthy tune takes.
        assertTrue(FieldDecodeProbeMs >= 8_000, "the probe window is too tight to be safe")
    }

    @Test
    fun `a picture that arrived settles it`() {
        assertFalse(probe(presentedVideo = true, playedMs = FieldDecodeProbeMs * 3))
    }

    @Test
    fun `nothing to probe on a source that is not field-coded`() {
        assertFalse(probe(fieldCoded = false))
    }

    @Test
    fun `a decoder already judged is never probed again`() {
        assertFalse(probe(verdict = FieldDecodeVerdict.HardwareDecodes))
        assertFalse(probe(verdict = FieldDecodeVerdict.HardwareEmitsNothing))
    }

    @Test
    fun `only a decoder known to emit nothing is opened in software`() {
        assertEquals(
            true,
            softwareDecodeRequired(fieldCoded = true, FieldDecodeVerdict.HardwareEmitsNothing),
        )
        assertEquals(
            false,
            softwareDecodeRequired(fieldCoded = true, FieldDecodeVerdict.Unknown),
        )
        assertEquals(
            false,
            softwareDecodeRequired(fieldCoded = true, FieldDecodeVerdict.HardwareDecodes),
        )
        // Progressive content never had a field-picture problem to begin with.
        assertEquals(
            false,
            softwareDecodeRequired(fieldCoded = false, FieldDecodeVerdict.HardwareEmitsNothing),
        )
    }

    /**
     * The reload is only evidence if it produced what hardware could not. A tune that is
     * black in software too was never the decoder's fault, and recording it as such would
     * strand the device on the slow path over an aerial fault.
     */
    @Test
    fun `the fallback is only believed when it produced a picture`() {
        assertEquals(
            FieldDecodeVerdict.HardwareEmitsNothing,
            verdictAfterFallback(softwarePresentedVideo = true),
        )
        assertEquals(
            FieldDecodeVerdict.Unknown,
            verdictAfterFallback(softwarePresentedVideo = false),
        )
    }
}
