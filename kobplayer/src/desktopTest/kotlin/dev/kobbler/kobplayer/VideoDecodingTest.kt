package dev.kobbler.kobplayer

import kotlin.test.Test
import kotlin.test.assertEquals

class VideoDecodingTest {

    @Test
    fun namesEachGpuByVendorAndOnlyDisambiguatesTwins() {
        val choices = linuxDecoderChoices(
            listOf(
                "/dev/dri/renderD128" to "0x8086",
                "/dev/dri/renderD129" to "0x10de",
                "/dev/dri/renderD130" to "0x1002",
                "/dev/dri/renderD131" to "0x1002",
                "/dev/dri/renderD132" to null,
            ),
        )
        assertEquals(
            listOf(
                VideoDecoding.Auto to "Auto",
                VideoDecoding.Gpu("/dev/dri/renderD128") to "Intel GPU",
                VideoDecoding.Gpu("/dev/dri/renderD129") to "NVIDIA GPU",
                VideoDecoding.Gpu("/dev/dri/renderD130") to "AMD GPU (renderD130)",
                VideoDecoding.Gpu("/dev/dri/renderD131") to "AMD GPU (renderD131)",
                VideoDecoding.Gpu("/dev/dri/renderD132") to "GPU (renderD132)",
                VideoDecoding.Software to "Software (CPU)",
            ),
            choices.map { it.decoding to it.label },
        )
    }

    @Test
    fun survivesAStoredKeyRoundTrip() {
        listOf(VideoDecoding.Auto, VideoDecoding.Software, VideoDecoding.Gpu("/dev/dri/renderD129")).forEach {
            assertEquals(it, VideoDecoding.fromKey(it.key))
        }
        assertEquals(VideoDecoding.Auto, VideoDecoding.fromKey(null))
        assertEquals(VideoDecoding.Auto, VideoDecoding.fromKey("garbage"))
    }
}
