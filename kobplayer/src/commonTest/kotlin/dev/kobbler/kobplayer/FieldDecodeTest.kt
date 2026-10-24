package dev.kobbler.kobplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Keeping a decoder that cannot show interlaced video from leaving the screen black.
 *
 * A decoder that accepts field pictures and emits nothing is not an error as far as VLC is
 * concerned — no event, no error state, nothing to recover from — so the only evidence is
 * a clock that runs while no new picture is presented.
 */
class FieldDecodeTest {

    private val unknown = FieldDecodeVerdict.Unknown
    private val known = FieldDecodeVerdict.HardwareEmitsNothing

    /** Plays [ms] of media clock in 500 ms polls, [newPictures] decides each poll's pictures. */
    private fun SilentDecoderWatch.play(
        ms: Long,
        from: Long = 0,
        hasVideoTrack: Boolean = true,
        newPictures: (Long) -> Boolean = { false },
    ): SilentDecoderWatch {
        var pictures = 0L
        var clock = from
        while (clock <= from + ms) {
            if (newPictures(clock)) pictures++
            sample(clock, pictures, hasVideoTrack)
            clock += 500
        }
        return this
    }

    @Test
    fun `sound with no picture past the window is the silent decoder`() {
        assertTrue(SilentDecoderWatch().play(SilentDecoderRescueMs).silent)
    }

    /** Some local files and IPTV providers put a keyframe only every 6 s; a live join waits for one. */
    @Test
    fun `sound with no picture inside the window is a stream still finding its keyframe`() {
        assertFalse(SilentDecoderWatch().play(SilentDecoderRescueMs - 500).silent)
        assertTrue(SilentDecoderRescueMs >= 6_000, "the window is shorter than a keyframe interval we carry")
    }

    /**
     * What the Tensor VPU did with a 1080i file: the first keyframe came out, then nothing,
     * forever. A picture having appeared is not the decoder working; pictures still coming is.
     */
    @Test
    fun `one picture and then nothing is still a silent decoder`() {
        assertTrue(SilentDecoderWatch().play(SilentDecoderRescueMs + 500) { it == 0L }.silent)
    }

    @Test
    fun `pictures that keep coming are never silence`() {
        assertFalse(SilentDecoderWatch().play(SilentDecoderRescueMs * 5) { true }.silent)
    }

    /** A seek moves the clock without the picture having had any time to follow it. */
    @Test
    fun `a seek is not time spent without a picture`() {
        val watch = SilentDecoderWatch().play(1_000)
        watch.sample(600_000, 0, true)
        assertFalse(watch.play(1_000, from = 600_500).silent)
    }

    /** Paused or buffering, the clock stands still and nothing is owed. */
    @Test
    fun `a clock that is not moving owes no picture`() {
        val watch = SilentDecoderWatch()
        repeat(100) { watch.sample(1_000, 0, true) }
        assertFalse(watch.silent)
    }

    @Test
    fun `a source with no video track has no picture to wait for`() {
        assertFalse(SilentDecoderWatch().play(SilentDecoderRescueMs * 2, hasVideoTrack = false).silent)
    }

    @Test
    fun `an untested decoder is watched on field-coded sources, a known silent one on the rest`() {
        assertTrue(watchesForSilentDecoder(fieldCoded = true, unknown))
        assertFalse(watchesForSilentDecoder(fieldCoded = false, unknown))
        // A field-coded source on a known silent decoder already opens in software.
        assertFalse(watchesForSilentDecoder(fieldCoded = true, known))
        assertTrue(watchesForSilentDecoder(fieldCoded = false, known))
        assertFalse(watchesForSilentDecoder(fieldCoded = true, FieldDecodeVerdict.HardwareDecodes))
        assertFalse(watchesForSilentDecoder(fieldCoded = false, FieldDecodeVerdict.HardwareDecodes))
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

    private val white = DecodedClip(frames = 1, meanLuma = 235)
    private val nothing = DecodedClip(frames = 0, meanLuma = null)
    private val black = DecodedClip(frames = 1, meanLuma = 16)

    @Test
    fun `the self-test believes a decoder that showed the interlaced clip`() {
        assertEquals(FieldDecodeVerdict.HardwareDecodes, selfTestVerdict(SelfTestRun(white, white)))
    }

    /** What the Tensor VPU does: the progressive control decodes, the interlaced clip never comes out. */
    @Test
    fun `a decoder that showed the control but not the interlaced clip emits nothing`() {
        assertEquals(FieldDecodeVerdict.HardwareEmitsNothing, selfTestVerdict(SelfTestRun(white, nothing)))
        assertEquals(FieldDecodeVerdict.HardwareEmitsNothing, selfTestVerdict(SelfTestRun(white, black)))
    }

    /**
     * A control that failed says the test could not run — a decoder busy elsewhere, a size it
     * will not take — not that fields are broken. The tune-time probe is left to decide.
     */
    @Test
    fun `a control that did not decode proves nothing`() {
        assertEquals(FieldDecodeVerdict.Unknown, selfTestVerdict(SelfTestRun(nothing, nothing)))
        assertEquals(FieldDecodeVerdict.Unknown, selfTestVerdict(SelfTestRun(black, white)))
    }

    @Test
    fun `a device with no hardware decoder already decodes fields in software`() {
        assertEquals(FieldDecodeVerdict.HardwareDecodes, selfTestVerdict(null))
    }
}
