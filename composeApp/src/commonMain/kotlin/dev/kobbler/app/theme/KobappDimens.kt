package dev.kobbler.app.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class KobappDimens(
    val s1: Dp = 4.dp,
    val s2: Dp = 8.dp,
    val s3: Dp = 12.dp,
    val s4: Dp = 16.dp,
    val s5: Dp = 24.dp,
    val s6: Dp = 32.dp,
    val s7: Dp = 48.dp,
    val s8: Dp = 64.dp,

    val r1: Dp = 6.dp,
    val r2: Dp = 10.dp,
    val r3: Dp = 14.dp,
    val r4: Dp = 20.dp,

    val minTouchTarget: Dp = 48.dp,
    val minTouchTargetIos: Dp = 44.dp,
    val rowHeight: Dp = 56.dp,
    val rowHeightCompact: Dp = 44.dp,

    val sidebarCollapsed: Dp = 72.dp,
    val sidebarTv: Dp = 64.dp,
    val sidebarExpanded: Dp = 240.dp,

    // The one portrait card width and the one landscape card width. Every poster
    // (grid cell, carousel tile) is posterWidth; every 16:9 card is wideCardWidth,
    // sized so its art is as tall as a poster card is wide. Navigation.kt rescales
    // both from the measured content width; nothing else may size a card.
    val posterWidth: Dp = 140.dp,
    val wideCardWidth: Dp = 280.dp,

    // A focused card's ring, halo and scale-up all paint outside its layout bounds;
    // a scrolling row clips exactly at its edge, so it has to reserve this much.
    val focusBleed: Dp = 8.dp,

    val railGap: Dp = 14.dp,
    val sectionGap: Dp = 28.dp,
    val pageGutter: Dp = 28.dp,
    val pageGutterTv: Dp = 40.dp,
    val pageGutterPhone: Dp = 18.dp,
)

@Immutable
data class KobappShapes(
    val card: Shape = RoundedCornerShape(10.dp),
    val poster: Shape = RoundedCornerShape(8.dp),
    val chip: Shape = RoundedCornerShape(999.dp),
    val pill: Shape = RoundedCornerShape(999.dp),
    val sheet: Shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
    val modal: Shape = RoundedCornerShape(18.dp),
    val hero: Shape = RoundedCornerShape(14.dp),
    val small: Shape = RoundedCornerShape(6.dp),
)

val LocalKobappDimens = compositionLocalOf { KobappDimens() }
val LocalKobappShapes = compositionLocalOf { KobappShapes() }
