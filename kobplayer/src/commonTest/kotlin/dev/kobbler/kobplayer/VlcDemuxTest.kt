package dev.kobbler.kobplayer

import kotlin.test.Test
import kotlin.test.assertEquals

class VlcDemuxTest {

    @Test
    fun matroskaSeeksToItsCues() {
        assertEquals("demux=mkv_trusted,any", vlcDemuxOption(MediaSource.Url("https://x/y.mkv")))
    }

    @Test
    fun mpegAudioSeeksBySampleRatherThanByItsEncoderEstimate() {
        assertEquals("demux=avformat,any", vlcDemuxOption(MediaSource.Url("https://x/a", mpegAudio = true)))
    }
}
