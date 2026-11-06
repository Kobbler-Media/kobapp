package dev.kobbler.app.ui.reader.layout

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.font.FontFamily
import dev.kobbler.app.ui.reader.ReaderTheme

@Immutable
data class ReaderTypography(
    val theme: ReaderTheme,
    val book: BookStyle = BookStyle.Book,
    val justify: Boolean = true,
    val publisherFonts: Map<String, FontFamily> = emptyMap(),
) {
    val lineHeightMultiplier: Float
        get() = if (theme.fontSize.value > 0f) theme.lineHeight.value / theme.fontSize.value else 1.5f
}
