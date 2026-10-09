package dev.kobbler.kobplayer

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrameFormatTest {

    @Test
    fun takesWhatTheDecoderProducesSoVlcNeverConverts() {
        mapOf(
            "I420" to PixelFormat.I420,
            "J420" to PixelFormat.I420,
            "VAOP" to PixelFormat.I420,
            "VDV0" to PixelFormat.I420,
            "VAO0" to PixelFormat.P010,
            "P010" to PixelFormat.P010,
            "I0AL" to PixelFormat.I0AL,
            "I444" to PixelFormat.I420,
            "RV32" to PixelFormat.I420,
        ).forEach { (proposed, expected) ->
            assertEquals(expected, PixelFormat.forDecoderChroma(proposed, sixteenBitTextures = true), proposed)
            assertEquals(PixelFormat.I420, PixelFormat.forDecoderChroma(proposed, sixteenBitTextures = false), proposed)
        }
    }

    @Test
    fun planesAreSimdAlignedAndRoundOddChromaUp() {
        data class Case(val format: PixelFormat, val planes: List<Pair<Int, Int>>, val rowBytes: List<Int>)
        listOf(
            Case(PixelFormat.I420, listOf(1281 to 721, 641 to 361, 641 to 361), listOf(1281, 641, 641)),
            Case(PixelFormat.I0AL, listOf(1281 to 721, 641 to 361, 641 to 361), listOf(2562, 1282, 1282)),
            Case(PixelFormat.P010, listOf(1281 to 721, 641 to 361), listOf(2562, 2564)),
        ).forEach { c ->
            val layout = FrameLayout(c.format, width = 1281, height = 721)
            assertEquals(c.planes, layout.planes.map { it.width to it.height }, c.format.name)
            layout.planes.forEachIndexed { i, plane ->
                assertEquals(0, plane.pitch % 64, "${c.format} plane $i pitch")
                assertTrue(plane.pitch >= c.rowBytes[i], "${c.format} plane $i pitch ${plane.pitch}")
                assertTrue(plane.lines >= plane.height)
                assertEquals(0L, plane.offset % 64)
            }
            val last = layout.planes.last()
            assertEquals(last.offset + last.pitch.toLong() * last.lines, layout.frameBytes)
        }
    }

    @Test
    fun negotiatesTheVisiblePictureNotTheDecoderPadding() {
        // libavcodec hands H.264 out as 1920x1090 (1088 + 2 lines of MC slack). Echoing that
        // back made VLC stretch 1080 visible lines over 1090.
        assertEquals(1920 to 1080, negotiatedSize(1920, 1090, visible = 1920 to 1080))
        assertEquals(1920 to 1090, negotiatedSize(1920, 1090, visible = null))
        assertEquals(1280 to 722, negotiatedSize(1280, 722, visible = 1920 to 1080))
        assertEquals(1280 to 722, negotiatedSize(1280, 722, visible = 0 to 0))
    }

    @Test
    fun convertsLimitedRangeYuvToRgb() {
        data class Case(val matrix: YuvMatrix, val yuv: Triple<Int, Int, Int>, val rgb: Triple<Float, Float, Float>)
        listOf(
            Case(YuvMatrix.BT601, Triple(81, 90, 240), Triple(1f, 0f, 0f)),
            Case(YuvMatrix.BT601, Triple(145, 54, 34), Triple(0f, 1f, 0f)),
            Case(YuvMatrix.BT709, Triple(63, 102, 240), Triple(1f, 0f, 0f)),
            Case(YuvMatrix.BT709, Triple(173, 42, 26), Triple(0f, 1f, 0f)),
            Case(YuvMatrix.BT709, Triple(16, 128, 128), Triple(0f, 0f, 0f)),
            Case(YuvMatrix.BT709, Triple(235, 128, 128), Triple(1f, 1f, 1f)),
        ).forEach { c ->
            val (r, g, b) = c.matrix.toRgb(c.yuv.first / 255f, c.yuv.second / 255f, c.yuv.third / 255f)
            val expected = c.rgb
            assertTrue(
                abs(r - expected.first) < 0.02f && abs(g - expected.second) < 0.02f && abs(b - expected.third) < 0.02f,
                "$c -> ($r, $g, $b)",
            )
        }
    }

    @Test
    fun picksHdMatrixForHdFrames() {
        assertEquals(YuvMatrix.BT601, YuvMatrix.forFrame(720, 576))
        assertEquals(YuvMatrix.BT601, YuvMatrix.forFrame(720, 480))
        assertEquals(YuvMatrix.BT709, YuvMatrix.forFrame(1280, 720))
        assertEquals(YuvMatrix.BT709, YuvMatrix.forFrame(1920, 800))
    }
}
