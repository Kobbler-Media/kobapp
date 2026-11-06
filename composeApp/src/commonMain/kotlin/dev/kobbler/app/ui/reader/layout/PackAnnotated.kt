package dev.kobbler.app.ui.reader.layout

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.kobbler.app.ui.reader.pack.Baseline
import dev.kobbler.app.ui.reader.pack.DropCap
import dev.kobbler.app.ui.reader.pack.PackTextStyle
import dev.kobbler.app.ui.reader.pack.Span
import dev.kobbler.app.ui.reader.pack.TextTransform
import dev.kobbler.app.ui.reader.pack.styleAt

internal fun buildPackText(
    spans: List<Span>,
    styles: List<PackTextStyle>,
    typography: ReaderTypography,
    sized: Boolean = true,
    dropCap: DropCap? = null,
    smallCapsMissing: (FontFamily?) -> Boolean = { false },
): AnnotatedString = buildAnnotatedString {
    val book = typography.book
    for (span in spans) {
        val style = styles.styleAt(span.style)
        val start = length
        val text = transformed(span.text, style.transform)
        val spanStyle = spanStyle(style, typography, sized, span.link != null || span.note != null)
        val synthetic = style.smallCaps && smallCapsMissing(spanStyle.fontFamily ?: typography.theme.fontFamily)
        append(if (synthetic) transformed(text, TextTransform.Uppercase) else text)
        addStyle(if (synthetic) spanStyle.copy(fontFeatureSettings = null) else spanStyle, start, length)
        if (synthetic) smallCapsFrom(text, start, spanStyle.fontSize)
        val wordSpacing = style.wordSpacing?.takeIf { book.spacing && it != 0f }
        if (wordSpacing != null) spaceWords(text, start, wordSpacing, style.letterSpacing?.takeIf { book.spacing } ?: 0f)
    }
    if (dropCap != null && dropCap.chars > 0 && length > 0 && sized) {
        val size = styles.styleAt(spans.first().style).size?.takeIf { typography.book.sizes } ?: 1f
        val dropSize = typography.theme.fontSize.value * size * dropCap.lines.coerceIn(2, 4) * DropCapScale
        addStyle(SpanStyle(fontSize = dropSize.sp), 0, dropCap.chars.coerceAtMost(length))
    }
}

private const val DropCapScale = 0.8f

internal fun transformed(text: String, transform: TextTransform?): String = when (transform) {
    null -> text
    TextTransform.Uppercase -> mapChars(text) { it.uppercaseChar() }
    TextTransform.Lowercase -> mapChars(text) { it.lowercaseChar() }
    TextTransform.Capitalize -> buildString(text.length) {
        var atWordStart = true
        for (c in text) {
            append(if (atWordStart && c.isLetter()) c.titlecaseChar() else c)
            atWordStart = c.isWhitespace()
        }
    }
}

private inline fun mapChars(text: String, map: (Char) -> Char): String =
    buildString(text.length) { for (c in text) append(map(c)) }

internal fun spanStyle(
    style: PackTextStyle,
    typography: ReaderTypography,
    sized: Boolean,
    isLink: Boolean,
): SpanStyle {
    val theme = typography.theme
    val book = typography.book
    val bookColor = if (book.colours) parseHexColor(style.color)?.takeIf { readableOn(theme.backgroundColor, it) } else null
    return SpanStyle(
        color = bookColor ?: if (isLink) theme.linkColor else Color.Unspecified,
        fontSize = if (sized) (theme.fontSize.value * (style.size?.takeIf { book.sizes } ?: 1f)).sp else TextUnit.Unspecified,
        fontWeight = FontWeight((style.weight ?: 400).coerceIn(1, 1000)),
        fontStyle = if (style.italic) FontStyle.Italic else FontStyle.Normal,
        fontFamily = familyOf(style.family, typography),
        fontFeatureSettings = if (style.smallCaps) "smcp" else null,
        letterSpacing = style.letterSpacing?.takeIf { book.spacing }?.em ?: TextUnit.Unspecified,
        baselineShift = style.shift?.let(::BaselineShift) ?: when (style.baseline) {
            Baseline.Super -> BaselineShift.Superscript
            Baseline.Sub -> BaselineShift.Subscript
            null -> null
        },
        background = spanBackground(style.background?.takeIf { book.colours }) ?: Color.Unspecified,
        textDecoration = decorationOf(style.underline, style.strike),
    )
}

internal fun familyOf(family: String?, typography: ReaderTypography): FontFamily? = when {
    family == "monospace" -> FontFamily.Monospace
    family == null || !typography.book.typeface -> null
    family == "serif" -> FontFamily.Serif
    family == "sans-serif" -> FontFamily.SansSerif
    else -> typography.publisherFonts[family]
}

private fun decorationOf(underline: Boolean, strike: Boolean): TextDecoration? = when {
    underline && strike -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
    underline -> TextDecoration.Underline
    strike -> TextDecoration.LineThrough
    else -> null
}
