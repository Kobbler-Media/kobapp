package dev.kobbler.kobplayer

import kotlin.test.Test
import kotlin.test.assertEquals

class AudioOutputTest {

    // The two Streamers: .128's HDMI takes AC3 only, .57's takes AC3, E-AC3 and TrueHD/MAT.
    private val ac3Only = encodingMask(ENCODING_AC3)
    private val dolby = encodingMask(ENCODING_AC3, ENCODING_E_AC3, ENCODING_DOLBY_TRUEHD)

    private data class Case(val mode: AudioOutputMode, val codec: String?, val mask: Long, val expected: String)

    @Test
    fun picksDeviceForModeCodecAndSink() {
        listOf(
            // Auto never hands multichannel PCM to HDMI: a sink that claims it and a TV that
            // cannot forward it over ARC is silence. Bitstream where the sink takes it, else stereo.
            Case(AudioOutputMode.AUTO, "a52 ", dolby, "encoded:$dolby"),
            Case(AudioOutputMode.AUTO, "eac3", dolby, "encoded:$dolby"),
            Case(AudioOutputMode.AUTO, "eac3", ac3Only, "stereo"),
            Case(AudioOutputMode.AUTO, "dts ", dolby, "stereo"),
            Case(AudioOutputMode.AUTO, "mp4a", dolby, "stereo"),
            Case(AudioOutputMode.AUTO, null, dolby, "stereo"),
            Case(AudioOutputMode.AUTO, "a52 ", 0L, "stereo"),
            Case(AudioOutputMode.STEREO, "a52 ", dolby, "stereo"),
            Case(AudioOutputMode.SURROUND, "a52 ", dolby, "pcm"),
            Case(AudioOutputMode.PASSTHROUGH, "dts ", dolby, "encoded:$dolby"),
            Case(AudioOutputMode.PASSTHROUGH, "a52 ", 0L, "pcm"),
        ).forEach { c ->
            assertEquals(c.expected, audioOutputDevice(c.mode, c.codec, c.mask), c.toString())
        }
    }
}
