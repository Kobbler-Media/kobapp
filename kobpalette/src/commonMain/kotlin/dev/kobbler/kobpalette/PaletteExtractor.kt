package dev.kobbler.kobpalette

import androidx.compose.ui.graphics.Color
import kotlin.math.abs

/**
 * Extracted color palette from an image.
 * Pure Kotlin implementation of median cut quantization — no platform dependencies.
 */
data class ColorPalette(
    val dominant: Color = Color.Unspecified,
    val vibrant: Color = Color.Unspecified,
    val darkVibrant: Color = Color.Unspecified,
    val lightVibrant: Color = Color.Unspecified,
    val muted: Color = Color.Unspecified,
    val darkMuted: Color = Color.Unspecified,
    val lightMuted: Color = Color.Unspecified,
) {
    /** Best accent color: vibrant > darkVibrant > muted > dominant */
    val accent: Color get() = listOf(vibrant, darkVibrant, muted, dominant).firstOrNull { it != Color.Unspecified } ?: Color.Unspecified
    /** Best dark color for backgrounds */
    val darkAccent: Color get() = listOf(darkVibrant, darkMuted, dominant).firstOrNull { it != Color.Unspecified } ?: Color.Unspecified
}

/**
 * Extract a color palette from raw ARGB pixel data using modified median cut quantization.
 *
 * @param pixels Array of ARGB pixel values (standard Android Bitmap format: 0xAARRGGBB)
 * @param maxColors Maximum colors to quantize to (default 16)
 */
fun extractPalette(pixels: IntArray, maxColors: Int = 16): ColorPalette {
    if (pixels.isEmpty()) return ColorPalette()

    val filtered = pixels.filter { pixel ->
        val a = (pixel shr 24) and 0xFF
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        a > 200 && !(r > 240 && g > 240 && b > 240) && !(r < 15 && g < 15 && b < 15)
    }
    if (filtered.isEmpty()) return ColorPalette()

    val swatches = medianCut(filtered, maxColors)
    if (swatches.isEmpty()) return ColorPalette()

    val sorted = swatches.sortedByDescending { it.population }
    val dominant = sorted.first().color

    var vibrant: Swatch? = null
    var darkVibrant: Swatch? = null
    var lightVibrant: Swatch? = null
    var muted: Swatch? = null
    var darkMuted: Swatch? = null
    var lightMuted: Swatch? = null

    for (swatch in sorted) {
        val hsl = rgbToHsl(swatch.r, swatch.g, swatch.b)
        val sat = hsl[1]
        val lum = hsl[2]

        when {
            sat > 0.35f && lum in 0.3f..0.7f && vibrant == null -> vibrant = swatch
            sat > 0.35f && lum < 0.3f && darkVibrant == null -> darkVibrant = swatch
            sat > 0.35f && lum > 0.7f && lightVibrant == null -> lightVibrant = swatch
            sat <= 0.35f && lum in 0.3f..0.7f && muted == null -> muted = swatch
            sat <= 0.35f && lum < 0.3f && darkMuted == null -> darkMuted = swatch
            sat <= 0.35f && lum > 0.7f && lightMuted == null -> lightMuted = swatch
        }
    }

    return ColorPalette(
        dominant = dominant,
        vibrant = vibrant?.color ?: Color.Unspecified,
        darkVibrant = darkVibrant?.color ?: Color.Unspecified,
        lightVibrant = lightVibrant?.color ?: Color.Unspecified,
        muted = muted?.color ?: Color.Unspecified,
        darkMuted = darkMuted?.color ?: Color.Unspecified,
        lightMuted = lightMuted?.color ?: Color.Unspecified,
    )
}

// -- Internals --

private data class Swatch(val r: Int, val g: Int, val b: Int, val population: Int) {
    val color: Color get() = Color(r / 255f, g / 255f, b / 255f)
}

private data class ColorBox(val colors: List<Int>) {
    val rRange get() = colors.maxOf { (it shr 16) and 0xFF } - colors.minOf { (it shr 16) and 0xFF }
    val gRange get() = colors.maxOf { (it shr 8) and 0xFF } - colors.minOf { (it shr 8) and 0xFF }
    val bRange get() = colors.maxOf { it and 0xFF } - colors.minOf { it and 0xFF }

    val longestAxis: Int get() = when (maxOf(rRange, gRange, bRange)) {
        rRange -> 0; gRange -> 1; else -> 2
    }

    fun split(): Pair<ColorBox, ColorBox> {
        val axis = longestAxis
        val sorted = colors.sortedBy { pixel ->
            when (axis) { 0 -> (pixel shr 16) and 0xFF; 1 -> (pixel shr 8) and 0xFF; else -> pixel and 0xFF }
        }
        val mid = sorted.size / 2
        return ColorBox(sorted.subList(0, mid)) to ColorBox(sorted.subList(mid, sorted.size))
    }

    fun average(): Swatch {
        var rSum = 0L; var gSum = 0L; var bSum = 0L
        for (pixel in colors) {
            rSum += (pixel shr 16) and 0xFF
            gSum += (pixel shr 8) and 0xFF
            bSum += pixel and 0xFF
        }
        val n = colors.size
        return Swatch((rSum / n).toInt(), (gSum / n).toInt(), (bSum / n).toInt(), n)
    }
}

private fun medianCut(pixels: List<Int>, maxColors: Int): List<Swatch> {
    val boxes = mutableListOf(ColorBox(pixels))
    while (boxes.size < maxColors) {
        val boxToSplit = boxes.maxByOrNull { maxOf(it.rRange, it.gRange, it.bRange) } ?: break
        if (boxToSplit.colors.size < 2) break
        boxes.remove(boxToSplit)
        val (a, b) = boxToSplit.split()
        if (a.colors.isNotEmpty()) boxes.add(a)
        if (b.colors.isNotEmpty()) boxes.add(b)
    }
    return boxes.filter { it.colors.isNotEmpty() }.map { it.average() }
}

private fun rgbToHsl(r: Int, g: Int, b: Int): FloatArray {
    val rf = r / 255f; val gf = g / 255f; val bf = b / 255f
    val cmax = maxOf(rf, gf, bf); val cmin = minOf(rf, gf, bf)
    val delta = cmax - cmin
    val l = (cmax + cmin) / 2f
    val s = if (delta == 0f) 0f else delta / (1f - abs(2f * l - 1f))
    val h = when {
        delta == 0f -> 0f
        cmax == rf -> ((gf - bf) / delta) % 6f
        cmax == gf -> (bf - rf) / delta + 2f
        else -> (rf - gf) / delta + 4f
    } * 60f
    return floatArrayOf(if (h < 0) h + 360f else h, s, l)
}
