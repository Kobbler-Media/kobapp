package dev.kobbler.app.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every text token has to stay legible on every surface it can land on.
 *
 * The palette is four near-identical dark surfaces — bg to elev3 is only 2.6:1 apart —
 * so a text tier that drifts down does not look subtle, it disappears. textXlo used to
 * sit at 1.61:1 on elev3 and 2.32:1 on bg, which is below the point where WCAG considers
 * anything readable at all, and it was carrying real labels.
 *
 * The tiering is what the thresholds encode: textHi/textMd/textLo can carry body copy, so
 * they hold AA on the surfaces body copy actually sits on and AA-large on elev3, which
 * only ever backs a transient focus state. textXlo is de-emphasised chrome by definition
 * and is held to AA-large everywhere.
 */
class TextContrastTest {

    private val colors = KobappColorsDefault

    private val bodySurfaces = mapOf(
        "bg" to colors.bg,
        "elev1" to colors.elev1,
        "elev2" to colors.elev2,
    )
    private val allSurfaces = bodySurfaces + ("elev3" to colors.elev3)

    private val bodyTiers = mapOf(
        "textHi" to colors.textHi,
        "textMd" to colors.textMd,
        "textLo" to colors.textLo,
    )

    private fun relativeLuminance(c: Color): Double {
        fun channel(v: Float): Double {
            val d = v.toDouble()
            return if (d <= 0.03928) d / 12.92 else ((d + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    @Test
    fun body_tiers_hold_aa_where_body_copy_sits() {
        for ((tierName, tier) in bodyTiers) {
            for ((surfaceName, surface) in bodySurfaces) {
                val ratio = contrast(tier, surface)
                assertTrue(
                    ratio >= 4.5,
                    "$tierName on $surfaceName is ${format(ratio)}:1, under AA's 4.5:1",
                )
            }
        }
    }

    @Test
    fun body_tiers_stay_readable_on_a_focused_row() {
        // elev3 backs focus and selection states rather than page content, so AA-large is
        // the bar — but text that lands there while focused still has to be readable.
        for ((tierName, tier) in bodyTiers) {
            val ratio = contrast(tier, colors.elev3)
            assertTrue(ratio >= 3.0, "$tierName on elev3 is ${format(ratio)}:1, under 3:1")
        }
    }

    @Test
    fun the_de_emphasised_tier_is_dim_but_never_invisible() {
        for ((surfaceName, surface) in allSurfaces) {
            val ratio = contrast(colors.textXlo, surface)
            assertTrue(
                ratio >= 3.0,
                "textXlo on $surfaceName is ${format(ratio)}:1, under 3:1",
            )
        }
    }

    @Test
    fun the_tiers_stay_distinguishable_from_each_other() {
        // Lifting a tier to clear AA is only half the job: if it lands on its neighbour the
        // hierarchy the palette is expressing stops being readable as a hierarchy.
        val ramp = listOf(
            "textHi" to colors.textHi,
            "textMd" to colors.textMd,
            "textLo" to colors.textLo,
            "textXlo" to colors.textXlo,
        )
        for (i in 0 until ramp.size - 1) {
            val (brighterName, brighter) = ramp[i]
            val (dimmerName, dimmer) = ramp[i + 1]
            val brighterOnBg = contrast(brighter, colors.bg)
            val dimmerOnBg = contrast(dimmer, colors.bg)
            assertTrue(
                brighterOnBg > dimmerOnBg * 1.15,
                "$brighterName (${format(brighterOnBg)}:1) and $dimmerName " +
                    "(${format(dimmerOnBg)}:1) are too close to read as separate tiers",
            )
        }
    }

    private fun format(v: Double): String {
        val scaled = (v * 100).toLong()
        return "${scaled / 100}.${(scaled % 100).toString().padStart(2, '0')}"
    }
}
