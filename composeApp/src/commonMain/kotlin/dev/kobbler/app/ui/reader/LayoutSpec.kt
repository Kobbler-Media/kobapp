package dev.kobbler.app.ui.reader

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.GenericFontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import dev.kobbler.app.platform.AppVersion
import dev.kobbler.app.ui.reader.layout.ReaderTypography

/** Everything a chapter is laid out for, besides the chapter itself. */
@Immutable
internal data class LayoutSpec(
    val typography: ReaderTypography,
    val pageSize: IntSize,
    val density: Density,
    val direction: LayoutDirection,
    val resolver: FontFamily.Resolver,
) {
    val drawable: Boolean get() = pageSize.width > 0 && pageSize.height > 0

    /**
     * What decides where the pages break, stable across launches. Colours are left out, so
     * a theme change keeps its breaks; the app version is in, so a release that paginates
     * differently does not trust breaks the last one made.
     */
    val fingerprint: String by lazy {
        val theme = typography.theme
        listOf(
            AppVersion.versionCode,
            pageSize.width, pageSize.height, density.density, density.fontScale, direction,
            theme.fontSize.value, theme.lineHeight.value, theme.horizontalPadding, theme.paragraphSpacing,
            theme.fontFamily.stableName(),
            typography.book.aspects.filter { it.movesText }.map { it.key }.sorted(), typography.justify,
            typography.publisherFonts.keys.sorted(),
        ).joinToString("|")
    }
}

private fun FontFamily.stableName(): String = when (this) {
    is GenericFontFamily -> name
    FontFamily.Default -> "default"
    else -> toString()
}
