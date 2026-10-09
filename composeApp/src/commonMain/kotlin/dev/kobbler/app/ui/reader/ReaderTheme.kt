package dev.kobbler.app.ui.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class ReaderTheme(
    val backgroundColor: Color,
    val textColor: Color,
    val linkColor: Color,
    val highlightColor: Color,
    val fontSize: TextUnit,
    val lineHeight: TextUnit,
    val paragraphSpacing: Int, // dp
    val horizontalPadding: Int, // dp
    val fontFamily: FontFamily = FontFamily.Default,
) {
    companion object {
        val Dark = ReaderTheme(
            backgroundColor = Color(0xFF1F1D19),
            textColor = Color(0xFFF5F2EC),
            linkColor = Color(0xFFD9B985),
            highlightColor = Color(0xFFD9B985).copy(alpha = 0.32f),
            fontSize = 16.sp,
            lineHeight = 26.sp,
            paragraphSpacing = 12,
            horizontalPadding = 20,
        )

        val Light = ReaderTheme(
            backgroundColor = Color(0xFFFAFAFA),
            textColor = Color(0xFF212121),
            linkColor = Color(0xFF1976D2),
            highlightColor = Color(0xFFFFEB3B).copy(alpha = 0.4f),
            fontSize = 16.sp,
            lineHeight = 26.sp,
            paragraphSpacing = 12,
            horizontalPadding = 20,
        )

        val Sepia = ReaderTheme(
            backgroundColor = Color(0xFFF5E6CA),
            textColor = Color(0xFF3E2723),
            linkColor = Color(0xFF795548),
            highlightColor = Color(0xFFFFCC80).copy(alpha = 0.5f),
            fontSize = 16.sp,
            lineHeight = 26.sp,
            paragraphSpacing = 12,
            horizontalPadding = 20,
        )
    }
}

enum class ReaderThemeMode { Dark, Light, Sepia }

enum class ReaderFontFamily(val label: String, val compose: FontFamily) {
    Default("Default", FontFamily.Default),
    Serif("Serif", FontFamily.Serif),
    Monospace("Mono", FontFamily.Monospace),
}

fun ReaderThemeMode.toTheme(
    fontSize: TextUnit = 16.sp,
    lineHeight: TextUnit = 26.sp,
    horizontalPadding: Int = 20,
    fontFamily: FontFamily = FontFamily.Default,
): ReaderTheme =
    when (this) {
        ReaderThemeMode.Dark -> ReaderTheme.Dark
        ReaderThemeMode.Light -> ReaderTheme.Light
        ReaderThemeMode.Sepia -> ReaderTheme.Sepia
    }.copy(
        fontSize = fontSize,
        lineHeight = lineHeight,
        horizontalPadding = horizontalPadding,
        fontFamily = fontFamily,
    )
