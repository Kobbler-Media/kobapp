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
