package dev.kobbler.kobplayer

import com.sun.jna.Memory
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class FramePainterTest {

    @Test
    fun paintsEveryDecoderFormatInItsTrueColour() {
        // BT.601 limited-range pure red: 8-bit (81, 90, 240), 10-bit (326, 361, 960).
        PixelFormat.entries.forEach { format ->
            val layout = FrameLayout(format, width = 64, height = 36)
            Memory(layout.frameBytes).use { memory ->
                fillSolid(memory, layout, if (format.bitDepth == 8) intArrayOf(81, 90, 240) else intArrayOf(326, 361, 960))
                assertPaintsRed(DecodedFrame(layout, memory), format.name)
            }
        }
    }

    private fun fillSolid(memory: Memory, layout: FrameLayout, yuv: IntArray) {
        val shift = if (layout.format.msbAligned) 16 - layout.format.bitDepth else 0
        layout.planes.forEachIndexed { index, plane ->
            val samples = if (layout.format.semiPlanar && index == 1) intArrayOf(yuv[1], yuv[2]) else intArrayOf(yuv[index])
            for (row in 0 until plane.height) {
                var at = plane.offset + row.toLong() * plane.pitch
                repeat(plane.width) {
                    samples.forEach { sample ->
                        if (layout.format.bytesPerSample == 1) {
                            memory.setByte(at, sample.toByte())
                        } else {
                            memory.setShort(at, (sample shl shift).toShort())
                        }
                        at += layout.format.bytesPerSample
                    }
                }
            }
        }
    }
}

internal fun assertPaintsRed(frame: DecodedFrame, label: String) {
    val surface = Surface.makeRasterN32Premul(frame.layout.width, frame.layout.height)
    FramePainter().use { painter ->
        painter.draw(surface.canvas, frame, Rect.makeWH(frame.layout.width.toFloat(), frame.layout.height.toFloat()))
    }
    val bitmap = Bitmap.makeFromImage(surface.makeImageSnapshot())
    val color = bitmap.getColor(frame.layout.width / 2, frame.layout.height / 2)
    val (r, g, b) = Triple(color shr 16 and 0xff, color shr 8 and 0xff, color and 0xff)
    assertTrue(abs(r - 255) <= 6 && g <= 6 && b <= 6, "$label painted ($r, $g, $b)")
}
