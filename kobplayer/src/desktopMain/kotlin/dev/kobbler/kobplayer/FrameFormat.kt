package dev.kobbler.kobplayer

internal enum class PixelFormat(
    val fourcc: String,
    val bitDepth: Int,
    val bytesPerSample: Int,
    val semiPlanar: Boolean,
    val msbAligned: Boolean,
) {
    I420("I420", bitDepth = 8, bytesPerSample = 1, semiPlanar = false, msbAligned = false),
    I0AL("I0AL", bitDepth = 10, bytesPerSample = 2, semiPlanar = false, msbAligned = false),
    P010("P010", bitDepth = 10, bytesPerSample = 2, semiPlanar = true, msbAligned = true),
    ;

    companion object {
        // Only what a decoder hands over untouched: VAAPI downloads 10-bit surfaces as P010
        // and 8-bit ones as I420, libavcodec emits I0AL for 10-bit. Anything rarer (4:2:2,
        // 4:4:4, 12-bit), or 10-bit when Skia offers no 16-bit texture, is converted to
        // I420 by VLC rather than grown a shader of its own.
        fun forDecoderChroma(chroma: String, sixteenBitTextures: Boolean): PixelFormat = when {
            !sixteenBitTextures -> I420
            chroma == "VAO0" || chroma == "P010" -> P010
            chroma == "I0AL" -> I0AL
            else -> I420
        }
    }
}

internal class PlaneLayout(val width: Int, val height: Int, val pitch: Int, val lines: Int, val offset: Long)

internal class FrameLayout(val format: PixelFormat, val width: Int, val height: Int) {
    val planes: List<PlaneLayout>
    val frameBytes: Long

    init {
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val shapes = if (format.semiPlanar) {
            listOf(Triple(width, height, 1), Triple(chromaWidth, chromaHeight, 2))
        } else {
            listOf(Triple(width, height, 1), Triple(chromaWidth, chromaHeight, 1), Triple(chromaWidth, chromaHeight, 1))
        }
        var offset = 0L
        planes = shapes.map { (w, h, channels) ->
            // 64-byte rows keep VLC's AVX-512 copy paths aligned; whole-row multiples keep
            // every following plane aligned too.
            val pitch = align(w * channels * format.bytesPerSample, 64)
            PlaneLayout(w, h, pitch, align(h, 16), offset).also { offset += pitch.toLong() * it.lines }
        }
        frameBytes = offset
    }

    private fun align(value: Int, to: Int) = (value + to - 1) / to * to
}

internal fun negotiatedSize(proposedWidth: Int, proposedHeight: Int, visible: Pair<Int, Int>?): Pair<Int, Int> {
    val (w, h) = visible ?: return proposedWidth to proposedHeight
    return if (w in 1..proposedWidth && h in 1..proposedHeight) w to h else proposedWidth to proposedHeight
}

internal enum class YuvMatrix(private val kr: Float, private val kb: Float) {
    BT601(0.299f, 0.114f),
    BT709(0.2126f, 0.0722f),
    ;

    class Coefficients(val offset: FloatArray, val toR: FloatArray, val toG: FloatArray, val toB: FloatArray)

    fun coefficients(bitDepth: Int): Coefficients {
        val step = (1 shl (bitDepth - 8)).toFloat()
        val max = ((1 shl bitDepth) - 1).toFloat()
        val lumaScale = max / (219f * step)
        val chromaScale = max / (224f * step)
        val kg = 1f - kr - kb
        return Coefficients(
            offset = floatArrayOf(16f * step / max, 128f * step / max, 128f * step / max),
            toR = floatArrayOf(lumaScale, 0f, 2f * (1f - kr) * chromaScale),
            toG = floatArrayOf(lumaScale, -2f * (1f - kb) * kb / kg * chromaScale, -2f * (1f - kr) * kr / kg * chromaScale),
            toB = floatArrayOf(lumaScale, 2f * (1f - kb) * chromaScale, 0f),
        )
    }

    fun toRgb(y: Float, u: Float, v: Float): Triple<Float, Float, Float> {
        val c = coefficients(8)
        val yuv = floatArrayOf(y - c.offset[0], u - c.offset[1], v - c.offset[2])
        fun dot(row: FloatArray) = row[0] * yuv[0] + row[1] * yuv[1] + row[2] * yuv[2]
        return Triple(dot(c.toR), dot(c.toG), dot(c.toB))
    }

    companion object {
        fun forFrame(width: Int, height: Int): YuvMatrix = if (width >= 1280 || height > 576) BT709 else BT601
    }
}
