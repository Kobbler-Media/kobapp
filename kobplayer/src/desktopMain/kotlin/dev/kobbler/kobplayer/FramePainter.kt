package dev.kobbler.kobplayer

import com.sun.jna.Pointer
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Data
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.SamplingMode

internal class DecodedFrame(val layout: FrameLayout, val memory: Pointer)

// Planes are wrapped in place and handed to Skia, which uploads them as textures; the
// YUV→RGB matrix and all scaling run in the shader, on whichever GPU Compose renders with.
internal class FramePainter : AutoCloseable {
    private val paint = Paint()
    private var frame: DecodedFrame? = null
    private var planes: List<Image> = emptyList()

    fun draw(canvas: Canvas, frame: DecodedFrame, dst: Rect) {
        if (frame !== this.frame) wrap(frame)
        val layout = frame.layout
        val scale = dst.height / layout.height
        // Cubic only pays off when magnifying; shrinking with it just costs taps.
        val lumaSampling = if (scale > 1.01f) SamplingMode.CATMULL_ROM else SamplingMode.LINEAR
        val format = layout.format
        val coefficients = YuvMatrix.forFrame(layout.width, layout.height).coefficients(format.bitDepth)
        val chroma = layout.planes[1]

        val builder = RuntimeShaderBuilder(if (format.semiPlanar) semiPlanarEffect else planarEffect)
        val children = planes.mapIndexed { i, image ->
            val sampling = when {
                i == 0 -> lumaSampling
                format.semiPlanar -> SamplingMode.DEFAULT
                else -> SamplingMode.LINEAR
            }
            image.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, sampling, null)
        }
        val names = if (format.semiPlanar) listOf("luma", "chroma") else listOf("luma", "cb", "cr")
        names.zip(children).forEach { (name, child) -> builder.child(name, child) }
        builder.uniform("chromaScale", chroma.width.toFloat() / layout.width, chroma.height.toFloat() / layout.height)
        if (format.semiPlanar) builder.uniform("chromaSize", chroma.width.toFloat(), chroma.height.toFloat())
        builder.uniform("sampleScale", sampleScale(format))
        builder.uniform("offset", coefficients.offset)
        builder.uniform("toR", coefficients.toR)
        builder.uniform("toG", coefficients.toG)
        builder.uniform("toB", coefficients.toB)

        val shader = builder.makeShader(
            Matrix33(dst.width / layout.width, 0f, dst.left, 0f, scale, dst.top, 0f, 0f, 1f),
        )
        paint.shader = shader
        canvas.drawRect(dst, paint)
        paint.shader = null
        shader.close()
        children.forEach { it.close() }
        builder.close()
    }

    fun forget() {
        planes.forEach { it.close() }
        planes = emptyList()
        frame = null
    }

    override fun close() {
        forget()
        paint.close()
    }

    private fun wrap(frame: DecodedFrame) {
        forget()
        val base = Pointer.nativeValue(frame.memory)
        val format = frame.layout.format
        val colorType = if (format.bytesPerSample == 2) checkNotNull(SkiaTextures.alpha16) else ColorType.ALPHA_8
        planes = frame.layout.planes.mapIndexed { i, plane ->
            // Interleaved UV is wrapped as one alpha texture twice as wide (see semiPlanarEffect).
            val width = if (format.semiPlanar && i == 1) plane.width * 2 else plane.width
            val bytes = plane.pitch.toLong() * plane.lines
            Data.makeWithoutCopy(base + plane.offset, bytes.toInt(), memoryOwnedBySink).use { data ->
                Image.makeRaster(ImageInfo(width, plane.height, colorType, ColorAlphaType.PREMUL), data, plane.pitch)
            }
        }
        this.frame = frame
    }

    private fun sampleScale(format: PixelFormat): Float {
        val max = ((1 shl format.bitDepth) - 1).toFloat()
        val stored = if (format.msbAligned) (1 shl (format.bytesPerSample * 8 - format.bitDepth)) * max else max
        return ((1L shl (format.bytesPerSample * 8)) - 1).toFloat() / stored
    }

    private companion object {
        // Skia only wants something to keep reachable; the planes belong to the sink's
        // FrameSet, which is not freed while this painter still wraps them.
        val memoryOwnedBySink: Data = Data.makeEmpty()

        // H.264/HEVC site chroma on the left luma column (MPEG-2 style), a quarter of a
        // chroma sample left of centre-sited sampling.
        const val HEADER = """
            uniform float2 chromaScale;
            uniform float sampleScale;
            uniform float3 offset;
            uniform float3 toR;
            uniform float3 toG;
            uniform float3 toB;
            half4 rgb(float3 yuv) {
                yuv = yuv * sampleScale - offset;
                return half4(half3(saturate(float3(dot(toR, yuv), dot(toG, yuv), dot(toB, yuv)))), 1);
            }
            float2 chromaAt(float2 p) { return p * chromaScale + float2(0.25, 0.0); }
        """

        val planarEffect: RuntimeEffect = RuntimeEffect.makeForShader(
            HEADER + """
            uniform shader luma;
            uniform shader cb;
            uniform shader cr;
            half4 main(float2 p) {
                float2 c = chromaAt(p);
                return rgb(float3(luma.eval(p).a, cb.eval(c).a, cr.eval(c).a));
            }
            """,
        )

        // Skia cannot wrap an RG16 texture from skiko (see SkiaTextures), so the UV plane is an
        // alpha texture with U and V in alternate columns, sampled nearest and filtered here.
        val semiPlanarEffect: RuntimeEffect = RuntimeEffect.makeForShader(
            HEADER + """
            uniform shader luma;
            uniform shader chroma;
            uniform float2 chromaSize;
            float2 uvAt(float2 texel) {
                texel = clamp(texel, float2(0), chromaSize - 1);
                return float2(
                    chroma.eval(float2(texel.x * 2 + 0.5, texel.y + 0.5)).a,
                    chroma.eval(float2(texel.x * 2 + 1.5, texel.y + 0.5)).a
                );
            }
            half4 main(float2 p) {
                float2 c = chromaAt(p) - 0.5;
                float2 i = floor(c);
                float2 f = c - i;
                float2 uv = mix(
                    mix(uvAt(i), uvAt(i + float2(1, 0)), f.x),
                    mix(uvAt(i + float2(0, 1)), uvAt(i + float2(1, 1)), f.x),
                    f.y
                );
                return rgb(float3(luma.eval(p).a, uv));
            }
            """,
        )
    }
}

internal object SkiaTextures {
    // skiko's ColorType lacks kRGB_F16F16F16x, so every entry after RGBA_F16 reaches the
    // native type one below its name (R16G16_UNORM is really A16_unorm; RG16 is out of reach
    // entirely). Probe for the one that behaves as A16 so this survives skiko fixing it.
    val alpha16: ColorType? by lazy { listOf(ColorType.A16_UNORM, ColorType.R16G16_UNORM).firstOrNull(::isAlpha16) }

    private fun isAlpha16(type: ColorType): Boolean = runCatching {
        val half = byteArrayOf(0, 0x80.toByte(), 0, 0, 0, 0, 0, 0)
        Image.makeRaster(ImageInfo(1, 1, type, ColorAlphaType.PREMUL), half, 8).use { image ->
            Bitmap().use { bitmap ->
                bitmap.allocN32Pixels(1, 1)
                image.readPixels(bitmap, 0, 0) && bitmap.getColor(0, 0).let { (it ushr 24) in 0x7f..0x80 && it and 0xffffff == 0 }
            }
        }
    }.getOrDefault(false)
}
