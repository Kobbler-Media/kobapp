package dev.kobbler.app.ui.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.theme.LocalFormFactor

/**
 * A detail page's title, the year set small after it. The year used to open a meta line
 * that already had to carry runtime, rating and certificate, and on a phone that line ran
 * to two.
 */
@Composable
internal fun DetailTitle(title: String, year: Int?) {
    val colors = KobappTheme.colors
    // The two-line split is a 10-foot/desktop flourish: it halves a title that already
    // wraps three times in a phone hero's 194dp column.
    val (head, tail) = if (LocalFormFactor.current == FormFactor.Phone) title.trim() to null else splitTitle(title)
    val style = KobappTheme.displayTitle.copy(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Medium,
        letterSpacing = (-0.03).em,
    )
    val yearSpan = SpanStyle(fontSize = style.fontSize * 0.5f, fontStyle = FontStyle.Normal, color = colors.textMd, letterSpacing = 0.em)
    Column {
        if (tail != null) Text(head, style = style, color = colors.textHi)
        Text(
            buildAnnotatedString {
                append(tail ?: head)
                if (year != null && year != 0) withStyle(yearSpan) { append(" $year") }
            },
            style = if (tail != null) style.copy(fontStyle = FontStyle.Italic) else style,
            color = colors.textHi,
        )
    }
}

private fun splitTitle(title: String): Pair<String, String?> {
    val trimmed = title.trim()
    if (trimmed.length < 18) return trimmed to null
    val mid = trimmed.length / 2
    val splitAt = (mid until trimmed.length).firstOrNull { trimmed[it] == ' ' }
        ?: (mid downTo 0).firstOrNull { trimmed[it] == ' ' }
        ?: return trimmed to null
    return trimmed.substring(0, splitAt) to trimmed.substring(splitAt + 1)
}

/**
 * The facts under a title, wrapping as they must. A dot separates two facts only when they
 * share a line: carried by either neighbour, a wrap left one dangling at an end.
 */
@Composable
internal fun DetailMeta(vararg items: (@Composable () -> Unit)?) {
    val present = items.filterNotNull()
    Layout(
        content = {
            present.forEach { it() }
            repeat((present.size - 1).coerceAtLeast(0)) { MetaDot() }
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val facts = measurables.take(present.size).map { it.measure(loose) }
        val dots = measurables.drop(present.size).map { it.measure(loose) }
        val gap = MetaGap.roundToPx()
        val lineGap = MetaLineGap.roundToPx()
        class Line(val start: Int, var end: Int, var width: Int, var height: Int)
        val lines = mutableListOf<Line>()
        facts.forEachIndexed { i, fact ->
            val line = lines.lastOrNull()
            val joined = if (line == null) 0 else line.width + gap + dots[i - 1].width + gap + fact.width
            if (line == null || joined > constraints.maxWidth) {
                lines += Line(i, i, fact.width, fact.height)
            } else {
                line.end = i
                line.width = joined
                line.height = maxOf(line.height, fact.height)
            }
        }
        val width = lines.maxOfOrNull { it.width } ?: 0
        val height = lines.sumOf { it.height } + lineGap * (lines.size - 1).coerceAtLeast(0)
        layout(width, height) {
            var y = 0
            lines.forEach { line ->
                var lx = 0
                for (i in line.start..line.end) {
                    if (i > line.start) {
                        val dot = dots[i - 1]
                        lx += gap
                        dot.place(lx, y + (line.height - dot.height) / 2)
                        lx += dot.width + gap
                    }
                    val fact = facts[i]
                    fact.place(lx, y + (line.height - fact.height) / 2)
                    lx += fact.width
                }
                y += line.height + lineGap
            }
        }
    }
}

private val MetaGap = 10.dp
private val MetaLineGap = 6.dp

internal fun metaText(text: String?, color: Color? = null): (@Composable () -> Unit)? =
    text?.takeIf { it.isNotBlank() }?.let { value ->
        { Text(value, style = KobappTheme.type.bodySm, color = color ?: KobappTheme.colors.textMd) }
    }

internal fun metaRating(rating: Float?): (@Composable () -> Unit)? = rating?.let { { RatingBadge(it) } }
