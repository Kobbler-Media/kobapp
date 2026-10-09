package dev.kobbler.app.ui.reader.epub

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import com.fleeksoft.ksoup.nodes.Element

internal data class CssProperties(
    val textAlign: TextAlign? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    val hidden: Boolean = false,
    val positionAbsolute: Boolean = false,
    val topPct: Float? = null,
    val topPx: Float? = null,
    val leftPct: Float? = null,
    val leftPx: Float? = null,
    val widthPagePx: Float? = null,
    val fontSizePct: Float? = null,
    val fontSizePx: Float? = null,
    val color: Color? = null,
)

internal fun parseCssStyles(cssText: String): Map<String, CssProperties> {
    val styles = mutableMapOf<String, CssProperties>()
    val cleaned = stripCssComments(cssText)
    var i = 0
    while (i < cleaned.length) {
        val openBrace = cleaned.indexOf('{', i)
        if (openBrace < 0) break
        val closeBrace = cleaned.indexOf('}', openBrace + 1)
        if (closeBrace < 0) break

        val selectors = cleaned.substring(i, openBrace).trim()
        val declarations = cleaned.substring(openBrace + 1, closeBrace).trim()
        i = closeBrace + 1

        if (selectors.isBlank() || declarations.isBlank()) continue
        if (selectors.startsWith("@")) continue

        val props = parseDeclarations(declarations)
        for (sel in selectors.split(",")) {
            val trimmed = sel.trim()
            if (trimmed.isNotBlank()) {
                styles[trimmed] = styles[trimmed]?.merge(props) ?: props
            }
        }
    }
    return styles
}

private fun stripCssComments(cssText: String): String = buildString(cssText.length) {
    var i = 0
    while (i < cssText.length) {
        if (i + 1 < cssText.length && cssText[i] == '/' && cssText[i + 1] == '*') {
            val end = cssText.indexOf("*/", i + 2)
            i = if (end >= 0) end + 2 else cssText.length
        } else {
            append(cssText[i])
            i++
        }
    }
}

/** Shared by stylesheet rules and inline `style=""` attributes. Percentages are stored
 *  already divided by 100; `px` values as page px. */
internal fun parseDeclarations(declarations: String): CssProperties {
    var textAlign: TextAlign? = null
    var bold = false
    var italic = false
    var underline = false
    var strikethrough = false
    var hidden = false
    var positionAbsolute = false
    var topPct: Float? = null
    var topPx: Float? = null
    var leftPct: Float? = null
    var leftPx: Float? = null
    var widthPagePx: Float? = null
    var fontSizePct: Float? = null
    var fontSizePx: Float? = null
    var color: Color? = null

    for (decl in declarations.split(";")) {
        val parts = decl.split(":", limit = 2)
        if (parts.size != 2) continue
        val prop = parts[0].trim().lowercase()
        val value = parts[1].trim().lowercase()
        when (prop) {
            "text-align" -> textAlign = when {
                "center" in value -> TextAlign.Center
                "right" in value -> TextAlign.Right
                "justify" in value -> TextAlign.Justify
                "left" in value -> TextAlign.Start
                else -> null
            }
            "font-weight" -> if ("bold" in value || value.toIntOrNull()?.let { it >= 700 } == true) bold = true
            "font-style" -> if ("italic" in value) italic = true
            "text-decoration" -> {
                if ("underline" in value) underline = true
                if ("line-through" in value) strikethrough = true
            }
            "display" -> if (value == "none") hidden = true
            "visibility" -> if (value == "hidden") hidden = true
            "position" -> if (value == "absolute") positionAbsolute = true
            "top" -> {
                topPct = value.removeSuffix("%").toFloatOrNull()?.let { it / 100f }
                if (topPct == null) topPx = value.removeSuffix("px").toFloatOrNull()
            }
            "left" -> {
                leftPct = value.removeSuffix("%").toFloatOrNull()?.let { it / 100f }
                if (leftPct == null) leftPx = value.removeSuffix("px").toFloatOrNull()
            }
            "width" -> widthPagePx = value.removeSuffix("px").toFloatOrNull()
            "font-size" -> {
                fontSizePct = value.removeSuffix("%").toFloatOrNull()
                if (fontSizePct == null) fontSizePx = value.removeSuffix("px").toFloatOrNull()
            }
            "color" -> color = parseCssColor(value)
        }
    }
    return CssProperties(
        textAlign, bold, italic, underline, strikethrough, hidden,
        positionAbsolute, topPct, topPx, leftPct, leftPx, widthPagePx, fontSizePct, fontSizePx,
        color,
    )
}

internal fun CssProperties.merge(other: CssProperties) = CssProperties(
    textAlign = other.textAlign ?: textAlign,
    bold = bold || other.bold,
    italic = italic || other.italic,
    underline = underline || other.underline,
    strikethrough = strikethrough || other.strikethrough,
    hidden = hidden || other.hidden,
    positionAbsolute = positionAbsolute || other.positionAbsolute,
    topPct = other.topPct ?: topPct,
    topPx = other.topPx ?: topPx,
    leftPct = other.leftPct ?: leftPct,
    leftPx = other.leftPx ?: leftPx,
    widthPagePx = other.widthPagePx ?: widthPagePx,
    fontSizePct = other.fontSizePct ?: fontSizePct,
    fontSizePx = other.fontSizePx ?: fontSizePx,
    color = other.color ?: color,
)

/** Parse a CSS `color` value (hex #rgb / #rrggbb / #rrggbbaa) into a [Color]. Named
 *  colors and rgb() fall through to null — the reader then uses its theme color. */
private fun parseCssColor(value: String): Color? {
    val hex = value.removePrefix("#")
    if (hex.length !in setOf(3, 6, 8)) return null
    val rgb = if (hex.length == 3) hex.map { c -> "$c$c" }.joinToString("") else hex
    val argb = if (rgb.length == 6) "ff$rgb" else rgb
    return argb.toLongOrNull(16)?.let { Color(it) }
}

internal fun lookupCss(element: Element, styles: Map<String, CssProperties>): CssProperties {
    var result = CssProperties()
    styles[element.tagName().lowercase()]?.let { result = result.merge(it) }
    for (cls in element.classNames()) {
        styles[".$cls"]?.let { result = result.merge(it) }
        styles["${element.tagName().lowercase()}.$cls"]?.let { result = result.merge(it) }
    }
    val id = element.id()
    if (id.isNotBlank()) {
        styles["#$id"]?.let { result = result.merge(it) }
    }
    return result
}

/** Stylesheet rules merged with the element's inline `style=""` (inline wins, as in CSS). */
internal fun elementCss(element: Element, styles: Map<String, CssProperties>): CssProperties =
    lookupCss(element, styles).merge(parseDeclarations(element.attr("style")))

internal fun extractCssAlign(element: Element, styles: Map<String, CssProperties>): TextAlign? {
    val cssProps = lookupCss(element, styles)
    if (cssProps.textAlign != null) return cssProps.textAlign
    val style = element.attr("style")
    if (style.isBlank()) return null
    return when {
        "text-align" in style && "center" in style -> TextAlign.Center
        "text-align" in style && "right" in style -> TextAlign.Right
        "text-align" in style && "justify" in style -> TextAlign.Justify
        "text-align" in style && "left" in style -> TextAlign.Start
        else -> null
    }
}

internal fun applyInlineCss(style: InlineStyle, css: String): InlineStyle {
    var result = style
    if ("font-weight" in css && ("bold" in css || "700" in css || "800" in css || "900" in css)) {
        result = result.copy(bold = true)
    }
    if ("font-style" in css && "italic" in css) {
        result = result.copy(italic = true)
    }
    if ("text-decoration" in css) {
        if ("underline" in css) result = result.copy(underline = true)
        if ("line-through" in css) result = result.copy(strikethrough = true)
    }
    return result
}