package dev.kobbler.app.ui.navigation

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kobbler.app.theme.KobappDimens
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhoneDimensTest {

    private val base = KobappDimens()

    // GridCells.FixedSize: floor((available + spacing) / (cell + spacing)), min 1.
    private fun columns(screenWidth: Dp, poster: Dp, alphabetBar: Boolean = false): Int {
        val end = base.pageGutterPhone + if (alphabetBar) 44.dp else 0.dp
        val available = screenWidth - base.pageGutterPhone - end
        return maxOf(1, floor((available + base.s3) / (poster + base.s3)).toInt())
    }

    private fun poster(screenWidth: Dp) = phoneDimens(base, screenWidth).posterWidth

    @Test
    fun staticDefaultCollapsesOnAPhone() {
        assertEquals(1, columns(360.dp, base.posterWidth, alphabetBar = true))
        assertEquals(2, columns(411.dp, base.posterWidth, alphabetBar = true))
    }

    @Test
    fun threeColumnsOnCommonPhoneWidths() {
        assertEquals(3, columns(360.dp, poster(360.dp)))
        assertEquals(3, columns(392.dp, poster(392.dp)))
        assertEquals(3, columns(411.dp, poster(411.dp)))
    }

    @Test
    fun widerScreensGainColumnsWithoutShrinkingCards() {
        assertEquals(4, columns(480.dp, poster(480.dp)))
        assertEquals(6, columns(800.dp, poster(800.dp)))
        listOf(360, 392, 411, 480, 600, 800).forEach { w ->
            val p = poster(w.dp)
            assertTrue(p >= 95.dp && p <= 125.dp, "poster at ${w}dp was $p")
        }
    }

    @Test
    fun wideCardTracksPoster() {
        val d = phoneDimens(base, 360.dp)
        assertEquals(d.posterWidth * 1.8f, d.wideCardWidth)
    }

    @Test
    fun unmeasuredWidthKeepsDefaults() {
        assertEquals(base, phoneDimens(base, 0.dp))
    }
}
