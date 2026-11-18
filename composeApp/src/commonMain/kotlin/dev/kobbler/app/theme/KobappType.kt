package dev.kobbler.app.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

val GeistFamily: FontFamily = FontFamily.SansSerif
val NewsreaderFamily: FontFamily = FontFamily.Serif

@Immutable
data class KobappTextStyles(
    val hero: TextStyle,
    val display: TextStyle,
    val h1: TextStyle,
    val h2: TextStyle,
    val h3: TextStyle,
    val body: TextStyle,
    val bodySm: TextStyle,
    val caption: TextStyle,
    val label: TextStyle,
    val labelSm: TextStyle,
    val readerTitle: TextStyle,
    val reader: TextStyle,
)

// Material 3 baseline type scale (androidx.compose.material3.tokens.TypeScaleTokens).
// Line heights are multiples of 4sp, ratio tightening as size grows: ~1.12 display,
// 1.25-1.4 headings, 1.43-1.5 body. 12sp is the Android floor for body text; only
// labelSm goes under it, at M3 labelSmall's 11sp, and never carries body copy.
val KobappTextStylesDefault = KobappTextStyles(
    hero = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 72.sp,
        lineHeight = 76.sp,
        letterSpacing = (-0.03).em,
    ),
    display = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.025).em,
    ),
    h1 = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.02).em,
    ),
    h2 = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.015).em,
    ),
    h3 = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.01).em,
    ),
    body = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.005).em,
    ),
    bodySm = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    caption = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    label = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.08.em,
    ),
    labelSm = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.08.em,
    ),
    readerTitle = TextStyle(
        fontFamily = NewsreaderFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.02).em,
    ),
    reader = TextStyle(
        fontFamily = NewsreaderFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        lineHeight = 28.sp,
    ),
)

// 10-foot scale. A 1080p TV is xhdpi, 960x540dp, so viewing distance is already in the dp
// and the phone scale reads as zoomed in there. These are the sizes TV apps set in 960dp:
// tv-material3's bodyMedium/labelLarge 14sp for body and buttons, 18sp row headings, 28sp
// detail titles, nothing under the 12sp floor. The reader keeps its larger book face.
val KobappTextStylesTv = KobappTextStyles(
    hero = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = (-0.025).em,
    ),
    display = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = (-0.02).em,
    ),
    h1 = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.015).em,
    ),
    h2 = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    h3 = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    body = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySm = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    caption = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    label = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.08.em,
    ),
    labelSm = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.06.em,
    ),
    readerTitle = TextStyle(
        fontFamily = NewsreaderFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.02).em,
    ),
    reader = TextStyle(
        fontFamily = NewsreaderFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
        lineHeight = 32.sp,
    ),
)

val LocalKobappType = staticCompositionLocalOf { KobappTextStylesDefault }
