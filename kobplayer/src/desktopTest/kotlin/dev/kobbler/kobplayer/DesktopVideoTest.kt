package dev.kobbler.kobplayer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopVideoTest {

    private data class Clip(
        val resource: String,
        val format: PixelFormat,
        val picture: Pair<Int, Int>,
        val videoSize: Pair<Int, Int>,
    )

    @Test
    fun decodesStraightIntoPaintableFrames() {
        DesktopPlayer.prepareLibraries()
        listOf(
            Clip("red_176x100_sar4-3.mkv", PixelFormat.I420, 176 to 100, 234 to 100),
            Clip("red_160x90_hevc10.mkv", PixelFormat.I0AL, 160 to 90, 160 to 90),
        ).forEach { clip ->
            val scope = Dispatchers.Default + SupervisorJob()
            val player = DesktopPlayer(scope, PlayerConfig(hardwareAcceleration = false))
            try {
                player.load(MediaSource.Url(resourceUrl(clip.resource)))
                val sink = player.video!!
                val frame = awaitFrame(sink)
                assertNotNull(frame, "${clip.resource} never produced a frame")
                assertEquals(clip.format, frame.layout.format, clip.resource)
                assertEquals(clip.picture, frame.layout.width to frame.layout.height, clip.resource)
                assertEquals(clip.videoSize, awaitVideoSize(player, clip.videoSize), clip.resource)
                assertPaintsRed(frame, clip.resource)
            } finally {
                player.release()
                scope.cancel()
            }
        }
    }

    private fun awaitVideoSize(player: DesktopPlayer, expected: Pair<Int, Int>): Pair<Int, Int> {
        val deadline = System.nanoTime() + 2_000_000_000
        while (player.videoSize.value != expected && System.nanoTime() < deadline) Thread.sleep(20)
        return player.videoSize.value
    }

    private fun awaitFrame(sink: VlcVideoSink): DecodedFrame? {
        val deadline = System.nanoTime() + 10_000_000_000
        while (System.nanoTime() < deadline) {
            sink.latestFrame()?.let { return it }
            Thread.sleep(20)
        }
        return null
    }

    @Test
    fun playsFileUrlsInEveryFormJavaWritesThem() {
        DesktopPlayer.prepareLibraries()
        val file = File(javaClass.getResource("/red_176x100_sar4-3.mkv")!!.toURI())
        listOf(file.toURI().toString(), file.toPath().toUri().toString()).forEach { url ->
            val scope = Dispatchers.Default + SupervisorJob()
            val player = DesktopPlayer(scope, PlayerConfig())
            try {
                player.load(MediaSource.Url(url))
                assertNotNull(awaitFrame(player.video!!), url)
            } finally {
                player.release()
                scope.cancel()
            }
        }
    }

    @Test
    fun fallsBackToCpuDecodingWhenVlcMustDrawSubtitles() {
        DesktopPlayer.prepareLibraries()
        val scope = Dispatchers.Default + SupervisorJob()
        val player = DesktopPlayer(scope, PlayerConfig())
        try {
            player.load(MediaSource.Url(resourceUrl("red_176x100_subtitled.mkv"), subtitlesOff = true))
            assertTrue(player.gpuDecodeRequested)
            assertNotNull(awaitFrame(player.video!!))
            val track = awaitSubtitleTrack(player)

            player.selectSubtitleTrack(track)

            assertFalse(player.gpuDecodeRequested)
            assertEquals(track, player.selectedSubtitleTrack.value)
            assertNotNull(awaitFrame(player.video!!))
        } finally {
            player.release()
            scope.cancel()
        }
    }

    private fun awaitSubtitleTrack(player: DesktopPlayer): TrackInfo {
        val deadline = System.nanoTime() + 10_000_000_000
        while (System.nanoTime() < deadline) {
            player.subtitleTracks.value.firstOrNull()?.let { return it }
            Thread.sleep(20)
        }
        error("no subtitle track")
    }

    @Test
    fun seeksMatroskaOverTheNetworkToItsCues() {
        assertTrue("demux=mkv_trusted,any" in vlcMediaOptions(MediaSource.Url("https://x/y.mkv"), PlayerConfig()))
    }

    // An m4b or mp3 can carry its cover as a video track; an audio player with no video sink
    // would decode it, and on desktop let VLC open a window of its own to show it.
    @Test
    fun audioOnlyPlayersNeverOpenVideo() {
        val src = MediaSource.Url("https://x/y.m4b")
        assertTrue("no-video" in vlcMediaOptions(src, PlayerConfig(audioOnly = true)))
        assertTrue("no-video" !in vlcMediaOptions(src, PlayerConfig()))
    }

    @Test
    fun asksForTheChosenDecoder() {
        val source = MediaSource.Url("http://x/y.mkv")
        val vendors = mapOf("/dev/dri/renderD128" to "0x8086", "/dev/dri/renderD129" to "0x10de")
        listOf(
            source to VideoDecoding.Gpu("/dev/dri/renderD128") to DecoderDevice("vaapi_drm", "/dev/dri/renderD128"),
            source to VideoDecoding.Gpu("/dev/dri/renderD129") to DecoderDevice("nvdec", null),
            source to VideoDecoding.Gpu("/dev/dri/renderD130") to DecoderDevice("vaapi_drm", "/dev/dri/renderD130"),
            source to VideoDecoding.Auto to DecoderDevice("any", null),
            source to VideoDecoding.Software to DecoderDevice("none", null),
            source.copy(subtitleOrdinal = 0) to VideoDecoding.Auto to DecoderDevice("none", null),
        ).forEach { (input, expected) ->
            val (src, decoding) = input
            assertEquals(expected, decoderDevice(src, decoding, vendors::get), "$decoding ${src.subtitleOrdinal}")
        }
    }
}
