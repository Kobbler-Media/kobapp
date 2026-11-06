package dev.kobbler.app.ui.reader.layout

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Compose has no word spacing; the spaces' own letter spacing stands in for it. */
internal fun AnnotatedString.Builder.spaceWords(text: String, start: Int, wordSpacing: Float, letterSpacing: Float) {
    text.forEachIndexed { i, c ->
        if (c == ' ') addStyle(SpanStyle(letterSpacing = (wordSpacing + letterSpacing).em), start + i, start + i + 1)
    }
}

/** Capitals for the lowercase letters, set smaller, for a font that has no small caps of its own. */
internal fun AnnotatedString.Builder.smallCapsFrom(text: String, start: Int, fontSize: TextUnit) {
    val size = if (fontSize == TextUnit.Unspecified) SmallCapsScale.em else (fontSize.value * SmallCapsScale).sp
    var run = -1
    for (i in 0..text.length) {
        val lower = i < text.length && text[i].isLowerCase()
        if (lower && run < 0) run = i
        if (!lower && run >= 0) {
            addStyle(SpanStyle(fontSize = size), start + run, start + i)
            run = -1
        }
    }
}

/** Whether a font ignores a request for small caps, from a sample measured with and without it. */
internal fun lacksSmallCaps(width: (smcp: Boolean) -> Float): Boolean = width(true) == width(false)

internal const val SmallCapsSample = "small caps sample"

/**
 * A book's highlight, translucent so the theme's text still reads on it; white is a book
 * saying "paper", which is no highlight at all.
 */
internal fun spanBackground(value: String?): Color? {
    val color = parseHexColor(value) ?: return null
    return color.copy(alpha = SpanTint).takeIf { color.luminance() < PaperLuminance }
}

private const val SmallCapsScale = 0.8f
private const val SpanTint = 0.35f
private const val PaperLuminance = 0.95f
