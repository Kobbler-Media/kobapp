package dev.kobbler.kobplayer

import kotlin.test.Test
import kotlin.test.assertEquals

class VideoFrameTest {

    private data class Case(
        val ratio: AspectRatio,
        val video: Pair<Int, Int>,
        val container: Pair<Int, Int>,
        val expected: Pair<Int, Int>,
    )

    @Test
    fun framesVideoInsideContainer() {
        listOf(
            // WALL-E: 2.42:1 scope on a 16:9 panel — letterboxed, never stretched to fill.
            Case(AspectRatio.FIT, 1280 to 528, 1920 to 1080, 1920 to 792),
            Case(AspectRatio.CROP, 1280 to 528, 1920 to 1080, 2618 to 1080),
            Case(AspectRatio.STRETCH, 1280 to 528, 1920 to 1080, 1920 to 1080),
            Case(AspectRatio.FIT, 1440 to 1080, 1920 to 1080, 1440 to 1080),
            Case(AspectRatio.CROP, 1440 to 1080, 1920 to 1080, 1920 to 1440),
            Case(AspectRatio.FIT, 1920 to 1080, 1920 to 1080, 1920 to 1080),
            Case(AspectRatio.FIT, 0 to 0, 1920 to 1080, 1920 to 1080),
        ).forEach { c ->
            val (vw, vh) = c.video
            val (cw, ch) = c.container
            assertEquals(c.expected, videoFrameSize(c.ratio, vw, vh, cw, ch), c.toString())
        }
    }

    @Test
    fun fillModesFallBackToFitWhenScreenTurnsAgainstThePicture() {
        listOf(
            Case(AspectRatio.CROP, 1920 to 1080, 1080 to 2400, 1080 to 608),
            Case(AspectRatio.STRETCH, 1920 to 1080, 1080 to 2400, 1080 to 608),
            Case(AspectRatio.CROP, 1080 to 1920, 2400 to 1080, 608 to 1080),
            Case(AspectRatio.CROP, 1920 to 1080, 2400 to 1080, 2400 to 1350),
            Case(AspectRatio.CROP, 1080 to 1080, 1080 to 2400, 2400 to 2400),
        ).forEach { c ->
            val (vw, vh) = c.video
            val (cw, ch) = c.container
            assertEquals(c.expected, videoFrameSize(c.ratio, vw, vh, cw, ch), c.toString())
        }
    }

    @Test
    fun trimmedFramePutsBakedInBarsOutsideTheContainer() {
        val scope = PictureCrop(x = 0.0, y = 0.125, width = 1.0, height = 0.75)
        val pillarbox = PictureCrop(x = 0.125, y = 0.0, width = 0.75, height = 1.0)
        val phoneScope = PictureCrop(x = 0.0, y = 0.12, width = 1.0, height = 0.76)
        listOf(
            Triple(AspectRatio.FIT, null, 1920 to 1080) to FrameRect(0, 0, 1920, 1080),
            Triple(AspectRatio.FIT, scope, 1920 to 1080) to FrameRect(0, 0, 1920, 1080),
            Triple(AspectRatio.CROP, null, 1920 to 1080) to FrameRect(0, 0, 1920, 1080),
            Triple(AspectRatio.CROP, scope, 1920 to 1080) to FrameRect(-320, -180, 2560, 1440),
            Triple(AspectRatio.FIT, pillarbox, 1440 to 1080) to FrameRect(-240, 0, 1920, 1080),
            Triple(AspectRatio.FIT, null, 1440 to 1080) to FrameRect(0, 135, 1440, 810),
            Triple(AspectRatio.FIT, null, 2400 to 1080) to FrameRect(240, 0, 1920, 1080),
            Triple(AspectRatio.FIT, phoneScope, 2400 to 1080) to FrameRect(0, -135, 2400, 1350),
            Triple(AspectRatio.FIT, PictureCrop(0.0, 0.0, 0.0, 0.0), 1920 to 1080) to FrameRect(0, 0, 1920, 1080),
        ).forEach { (case, expected) ->
            val (ratio, crop, container) = case
            assertEquals(expected, videoFrameRect(ratio, 1920, 1080, container.first, container.second, crop), case.toString())
        }
    }

    @Test
    fun visiblePartOfTheFrameIsWhatTheContainerShows() {
        assertEquals(FrameRect(0, 135, 2400, 1080), FrameRect(0, -135, 2400, 1350).visibleIn(2400, 1080))
        assertEquals(FrameRect(240, 0, 1440, 1080), FrameRect(-240, 0, 1920, 1080).visibleIn(1440, 1080))
        assertEquals(FrameRect(0, 0, 1920, 1080), FrameRect(240, 0, 1920, 1080).visibleIn(2400, 1080))
    }

    @Test
    fun appliesSampleAspectToStoredWidth() {
        assertEquals(1024 to 576, displaySize(720, 576, sarNum = 64, sarDen = 45))
        assertEquals(1280 to 528, displaySize(1280, 528, sarNum = 1, sarDen = 1))
        assertEquals(1280 to 528, displaySize(1280, 528, sarNum = 0, sarDen = 0))
    }

    @Test
    fun oversizesSurfaceSoDecoderPaddingFallsOutsideTheFrame() {
        listOf(
            null to (1920 to 1080),
            DecodedPicture(1920, 1080, 1920, 1080) to (1920 to 1080),
            // Software decode of 1080p: 8 padding rows below the picture.
            DecodedPicture(1920, 1088, 1920, 1080) to (1920 to 1088),
            DecodedPicture(1280, 736, 1280, 720) to (1920 to 1104),
            DecodedPicture(0, 0, 0, 0) to (1920 to 1080),
            // A closing vout reports its picture gone; a surface shrunk to it never regrows.
            DecodedPicture(0, 0, 2160, 1080) to (1920 to 1080),
        ).forEach { (picture, expected) ->
            assertEquals(expected, decodedSurfaceSize(1920, 1080, picture), picture.toString())
        }
    }
}
