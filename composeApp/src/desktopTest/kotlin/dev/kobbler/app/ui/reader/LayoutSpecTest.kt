package dev.kobbler.app.ui.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import dev.kobbler.app.ui.reader.layout.BookStyle
import dev.kobbler.app.ui.reader.layout.ReaderTypography
import dev.kobbler.app.ui.reader.layout.StyleAspect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class LayoutSpecTest {
    private val base = LayoutSpec(
        ReaderTypography(ReaderTheme.Dark), IntSize(1080, 2000), Density(2.75f), LayoutDirection.Ltr, createFontFamilyResolver(),
    )

    private fun ReaderTypography.withTheme(change: ReaderTheme.() -> ReaderTheme) = copy(theme = theme.change())

    @Test
    fun `colours leave the fingerprint alone`() {
        val light = base.copy(typography = base.typography.withTheme { ReaderTheme.Light.copy(fontSize = fontSize, lineHeight = lineHeight) })
        assertEquals(base.fingerprint, light.fingerprint)
        assertEquals(base.fingerprint, base.copy(typography = base.typography.withTheme { copy(textColor = Color.Red) }).fingerprint)
        assertEquals(base.fingerprint, base.copy(resolver = createFontFamilyResolver()).fingerprint)
        val bookColours = base.copy(typography = base.typography.copy(book = BookStyle.Book.with(StyleAspect.Colours, book = false)))
        assertEquals(base.fingerprint, bookColours.fingerprint, "the book's colours move no line")
    }

    @Test
    fun `whatever moves a line or a page changes the fingerprint`() {
        val variants = listOf(
            base.copy(pageSize = IntSize(1080, 1999)),
            base.copy(density = Density(2.75f, fontScale = 1.15f)),
            base.copy(direction = LayoutDirection.Rtl),
            base.copy(typography = base.typography.withTheme { copy(fontSize = 17.sp) }),
            base.copy(typography = base.typography.withTheme { copy(lineHeight = 30.sp) }),
            base.copy(typography = base.typography.withTheme { copy(horizontalPadding = 24) }),
            base.copy(typography = base.typography.withTheme { copy(fontFamily = FontFamily.Serif) }),
            *StyleAspect.entries.filter { it.movesText }.map { aspect ->
                base.copy(typography = base.typography.copy(book = BookStyle.Book.with(aspect, book = false)))
            }.toTypedArray(),
            base.copy(typography = base.typography.copy(justify = false)),
            base.copy(typography = base.typography.copy(publisherFonts = mapOf("Book Serif" to FontFamily.Serif))),
        )
        for (variant in variants) assertNotEquals(base.fingerprint, variant.fingerprint, variant.toString())
        assertEquals(variants.size, variants.map { it.fingerprint }.toSet().size)
    }
}
