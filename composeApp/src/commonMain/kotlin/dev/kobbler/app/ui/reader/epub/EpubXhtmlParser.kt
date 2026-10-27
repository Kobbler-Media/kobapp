package dev.kobbler.app.ui.reader.epub

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.Node
import com.fleeksoft.ksoup.nodes.TextNode

private val BLOCK_TAGS = setOf(
    "p", "div", "section", "article", "aside", "header", "footer", "nav",
    "h1", "h2", "h3", "h4", "h5", "h6",
    "blockquote", "ul", "ol", "pre", "hr", "br", "table",
    "figure", "figcaption", "main", "details", "summary",
    "img", "image", "svg",
)

private val HEADING_TAGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")

private val CONTAINER_TAGS = setOf(
    "div", "section", "article", "aside", "header", "footer", "nav",
    "main", "details", "figure",
)

fun parseXhtmlToBlocks(
    xhtml: String,
    includeHidden: Boolean = false,
    cssProvider: ((String) -> String?)? = null,
): List<XhtmlBlock> {
    val doc = Ksoup.parse(xhtml)

    val cssText = buildString {
        for (style in doc.select("style")) appendLine(style.data())
        if (cssProvider != null) {
            for (link in doc.select("link[rel=stylesheet]")) {
                val href = link.attr("href")
                if (href.isNotBlank()) cssProvider(href)?.let { appendLine(it) }
            }
        }
    }
    val styles = if (cssText.isNotBlank()) parseCssStyles(cssText) else emptyMap()

    // Fixed-layout pages are recognised in BOTH passes: the render pass shows the
    // positioned text over the artwork, the TTS pass reads the same fragments — the
    // block lists stay identical so TTS word block indices line up with the renderer.
    val fixed = extractFixedPage(doc.body(), styles)
    if (fixed != null) {
        return listOf(fixed) + elementToBlocks(doc.body(), styles)
    }
    if (!includeHidden) stripHiddenElements(doc.body(), styles)
    return elementToBlocks(doc.body(), styles)
}

/** The gen-3 KEPUB fixed-layout pattern: a `kobo-fullpage` div holding the page artwork
 *  (svg > image) next to a hidden `kobo-page-text` div whose positioned fragments are the
 *  visible story text. Kobo ships the layer hidden (devices read it for TTS/search only),
 *  but the stylesheet places each fragment over the artwork — that is what the page looks
 *  like, and what the web reader renders. Both source divs are removed from [body] so the
 *  rest of the page parses normally. Returns null (leaving the DOM untouched) when the
 *  pattern is absent or carries no positioned text. */
private fun extractFixedPage(body: Element, styles: Map<String, CssProperties>): XhtmlBlock.FixedPage? {
    val fullpage = body.select("div.kobo-fullpage").firstOrNull { it.select("image").isNotEmpty() }
        ?: return null
    val textLayer = fullpage.parent()?.children()
        ?.filterIsInstance<Element>()
        ?.firstOrNull { it.hasClass("kobo-page-text") }
        ?: return null
    val img = fullpage.select("image").firstOrNull() ?: return null
    val src = img.attr("src").ifBlank { img.attr("xlink:href") }
    if (src.isBlank()) return null
    val image = XhtmlBlock.Image(
        src = src,
        alt = img.attr("alt").ifBlank { null },
        align = TextAlign.Center,
        width = img.attr("width").toIntOrNull(),
        height = img.attr("height").toIntOrNull(),
    )
    val page = XhtmlBlock.FixedPage(image, emptyList())
    val fragments = extractFragments(textLayer, styles, page.pageWidth.toFloat(), page.pageHeight.toFloat())
    if (fragments.isEmpty()) return null
    fullpage.remove()
    textLayer.remove()
    return page.copy(fragments = fragments)
}

/** Collect the absolutely-positioned text fragments of a `kobo-page-text` layer, in DOM
 *  (reading) order. Positions and widths come from the stylesheet (or inline style); a
 *  fragment's children are not walked — fragments do not nest. */
private fun extractFragments(
    layer: Element,
    styles: Map<String, CssProperties>,
    pageW: Float,
    pageH: Float,
): List<OverlayFragment> {
    val out = mutableListOf<OverlayFragment>()
    fun walk(el: Element) {
        for (child in el.children()) {
            val css = elementCss(child, styles)
            val positioned = css.positionAbsolute && (css.topPct != null || css.topPx != null)
            if (positioned) {
                if (child.wholeText().isNotBlank()) {
                    out.add(
                        OverlayFragment(
                            topPct = css.topPct ?: (css.topPx ?: 0f) / pageH,
                            leftPct = css.leftPct ?: (css.leftPx ?: 0f) / pageW,
                            widthPagePx = css.widthPagePx ?: pageW,
                            fontSizePagePx = resolveFragmentFontSize(child, styles),
                            color = resolveFragmentColor(child, styles),
                            align = css.textAlign ?: TextAlign.Start,
                            spans = elementToSpans(child, InlineStyle(), styles),
                        ),
                    )
                }
            } else {
                walk(child)
            }
        }
    }
    walk(layer)
    return out
}

/** Resolve a fragment's font size in page px: walk from the fragment div down to the
 *  deepest text-bearing span and apply each font-size declaration in ancestor→descendant
 *  order (a % is relative to the parent's size, as in CSS). The base is the CSS default
 *  16px — these stylesheets never set a font-size on html/body. */
private fun resolveFragmentFontSize(div: Element, styles: Map<String, CssProperties>, base: Float = 16f): Float {
    val chain = mutableListOf<Element>()
    fun findPath(e: Element) {
        chain.add(e)
        for (child in e.children()) {
            if (child is Element && child.wholeText().isNotBlank()) {
                findPath(child)
                return
            }
        }
    }
    findPath(div)
    var size = base
    for (el in chain) {
        val css = elementCss(el, styles)
        when {
            css.fontSizePx != null -> size = css.fontSizePx!!
            css.fontSizePct != null -> size *= css.fontSizePct!! / 100f
        }
    }
    return size
}

/** Deepest `color` declaration on the fragment's text chain — the stylesheet's choice is
 *  the page's design (these books put dark text on light artwork), so it must not follow
 *  the reader's theme. Null when the stylesheet never names one. */
private fun resolveFragmentColor(div: Element, styles: Map<String, CssProperties>): Color? {
    val chain = mutableListOf<Element>()
    fun findPath(e: Element) {
        chain.add(e)
        for (child in e.children()) {
            if (child is Element && child.wholeText().isNotBlank()) {
                findPath(child)
                return
            }
        }
    }
    findPath(div)
    var color: Color? = null
    for (el in chain) {
        elementCss(el, styles).color?.let { color = it }
    }
    return color
}

/** Hidden content is dropped from the render pass — the `hidden` attribute, inline
 *  `display:none`/`visibility:hidden`, or a stylesheet rule setting either. Search and
 *  TTS parse with includeHidden so they still see it (that is how fixed-layout pages
 *  carry their text). */
private fun stripHiddenElements(parent: Element, styles: Map<String, CssProperties>) {
    for (child in parent.children().toList()) {
        if (isHidden(child, styles)) child.remove()
        else stripHiddenElements(child, styles)
    }
}

private fun isHidden(element: Element, styles: Map<String, CssProperties>): Boolean {
    if (element.hasAttr("hidden")) return true
    val inline = element.attr("style")
    if (inline.isNotBlank() && inlineHides(inline)) return true
    return lookupCss(element, styles).hidden
}

private fun inlineHides(css: String): Boolean {
    for (decl in css.split(';')) {
        val parts = decl.split(':', limit = 2)
        if (parts.size != 2) continue
        val prop = parts[0].trim().lowercase()
        val value = parts[1].trim().lowercase()
        if (prop == "display" && value == "none") return true
        if (prop == "visibility" && value == "hidden") return true
    }
    return false
}

private fun elementToBlocks(parent: Element, styles: Map<String, CssProperties>): List<XhtmlBlock> {
    val blocks = mutableListOf<XhtmlBlock>()

    for (child in parent.childNodes()) {
        when (child) {
            is TextNode -> {
                val text = child.getWholeText()
                if (text.isNotBlank()) {
                    blocks.add(XhtmlBlock.Paragraph(listOf(XhtmlSpan.Text(text))))
                }
            }
            is Element -> handleElementChild(child, styles, blocks)
        }
    }
    return blocks
}

private fun handleElementChild(
    child: Element,
    styles: Map<String, CssProperties>,
    blocks: MutableList<XhtmlBlock>,
) {
    val tag = child.tagName().lowercase()
    val hasImages = { child.select("img, image").isNotEmpty() }
    when {
        tag in HEADING_TAGS -> {
            val level = tag[1].digitToInt()
            if (hasImages()) {
                blocks.addAll(splitBlockAroundImages(child, styles))
            } else {
                val spans = elementToSpans(child, InlineStyle(), styles)
                if (spans.isNotEmpty()) blocks.add(XhtmlBlock.Heading(level, spans))
            }
        }
        tag == "p" || tag == "figcaption" || tag == "summary" -> {
            if (hasImages()) {
                blocks.addAll(splitBlockAroundImages(child, styles))
            } else {
                val align = extractCssAlign(child, styles)
                val spans = elementToSpans(child, InlineStyle(), styles)
                if (spans.isNotEmpty()) blocks.add(XhtmlBlock.Paragraph(spans, align))
            }
        }
        tag in CONTAINER_TAGS -> {
            val hasBlockChildren = child.children().any { it.tagName().lowercase() in BLOCK_TAGS }
            if (hasBlockChildren) {
                blocks.addAll(elementToBlocks(child, styles))
            } else {
                val align = extractCssAlign(child, styles)
                val spans = elementToSpans(child, InlineStyle(), styles)
                if (spans.isNotEmpty()) blocks.add(XhtmlBlock.Paragraph(spans, align))
            }
        }
        tag == "blockquote" -> blocks.add(XhtmlBlock.BlockQuote(elementToBlocks(child, styles)))
        tag == "ul" || tag == "ol" -> {
            val items = child.children()
                .filter { it.tagName().equals("li", ignoreCase = true) }
                .map { li ->
                    val hasBlocks = li.children().any { it.tagName().lowercase() in BLOCK_TAGS }
                    val spans = if (hasBlocks) flattenToSpans(li, InlineStyle(), styles)
                    else elementToSpans(li, InlineStyle(), styles)
                    ListItem(spans)
                }
            if (items.isNotEmpty()) blocks.add(XhtmlBlock.ListBlock(items, ordered = tag == "ol"))
        }
        tag == "pre" -> {
            val text = child.wholeText()
            if (text.isNotBlank()) blocks.add(XhtmlBlock.CodeBlock(text))
        }
        tag == "hr" -> blocks.add(XhtmlBlock.Divider)
        tag == "br" -> blocks.add(XhtmlBlock.Spacer)
        tag == "img" || tag == "image" -> imageBlock(child, styles)?.let(blocks::add)
        tag == "svg" -> blocks.addAll(svgImageBlocks(child))
        tag == "table" -> {
            val text = child.wholeText().trim()
            if (text.isNotBlank()) {
                blocks.add(XhtmlBlock.Paragraph(listOf(XhtmlSpan.Text(text))))
            }
        }
        else -> {
            val hasBlockChildren = child.children().any { it.tagName().lowercase() in BLOCK_TAGS }
            if (hasBlockChildren) {
                blocks.addAll(elementToBlocks(child, styles))
            } else if (hasImages()) {
                blocks.addAll(splitBlockAroundImages(child, styles))
            } else {
                val align = extractCssAlign(child, styles)
                val spans = elementToSpans(child, InlineStyle(), styles)
                if (spans.isNotEmpty()) blocks.add(XhtmlBlock.Paragraph(spans, align))
            }
        }
    }
}

private fun imageBlock(node: Element, styles: Map<String, CssProperties>): XhtmlBlock.Image? {
    val src = node.attr("src").ifBlank { node.attr("xlink:href") }
    if (src.isBlank()) return null
    return XhtmlBlock.Image(
        src = src,
        alt = node.attr("alt").ifBlank { null },
        align = extractCssAlign(node, styles),
        width = node.attr("width").toIntOrNull(),
        height = node.attr("height").toIntOrNull(),
    )
}

// Cover/title pages frequently embed the cover as <svg><image xlink:href="..."/></svg>.
// Extract any <image> descendants; ignore other SVG content (paths, shapes, text).
private fun svgImageBlocks(svg: Element): List<XhtmlBlock> = svg.select("image").mapNotNull { img ->
    val src = img.attr("src").ifBlank { img.attr("xlink:href") }
    if (src.isBlank()) null else XhtmlBlock.Image(
        src = src,
        alt = img.attr("alt").ifBlank { null },
        align = TextAlign.Center,
        width = img.attr("width").toIntOrNull(),
        height = img.attr("height").toIntOrNull(),
    )
}

private fun splitBlockAroundImages(element: Element, styles: Map<String, CssProperties>): List<XhtmlBlock> {
    val blocks = mutableListOf<XhtmlBlock>()
    val pendingSpans = mutableListOf<XhtmlSpan>()
    val align = extractCssAlign(element, styles)

    fun flushSpans() {
        if (pendingSpans.isNotEmpty()) {
            blocks.add(XhtmlBlock.Paragraph(pendingSpans.toList(), align))
            pendingSpans.clear()
        }
    }

    fun walkNodes(nodes: List<Node>, style: InlineStyle) {
        for (node in nodes) {
            when (node) {
                is TextNode -> {
                    val text = node.getWholeText()
                    if (text.isNotBlank() || (text.isNotEmpty() && pendingSpans.isNotEmpty())) {
                        pendingSpans.add(XhtmlSpan.Text(text, style))
                    }
                }
                is Element -> {
                    val tag = node.tagName().lowercase()
                    when (tag) {
                        "img", "image" -> {
                            flushSpans()
                            val src = node.attr("src").ifBlank { node.attr("xlink:href") }
                            if (src.isNotBlank()) {
                                val imgAlign = extractCssAlign(node, styles)
                                blocks.add(XhtmlBlock.Image(
                                    src, node.attr("alt").ifBlank { null }, imgAlign ?: align,
                                    width = node.attr("width").toIntOrNull(),
                                    height = node.attr("height").toIntOrNull(),
                                ))
                            }
                        }
                        "br" -> pendingSpans.add(XhtmlSpan.LineBreak())
                        "svg" -> {
                            val imgs = svgImageBlocks(node)
                            if (imgs.isNotEmpty()) {
                                flushSpans()
                                blocks.addAll(imgs)
                            }
                        }
                        else -> {
                            if (node.select("img, image").isNotEmpty()) {
                                walkNodes(node.childNodes(), applyElementStyle(node, style, styles))
                            } else {
                                val childStyle = applyElementStyle(node, style, styles)
                                pendingSpans.addAll(childNodesToSpans(node.childNodes(), childStyle, styles))
                            }
                        }
                    }
                }
            }
        }
    }

    walkNodes(element.childNodes(), InlineStyle())
    flushSpans()
    return blocks
}

private fun elementToSpans(
    element: Element,
    parentStyle: InlineStyle,
    styles: Map<String, CssProperties>,
): List<XhtmlSpan> = childNodesToSpans(element.childNodes(), applyElementStyle(element, parentStyle, styles), styles)

private fun childNodesToSpans(
    nodes: List<Node>,
    style: InlineStyle,
    styles: Map<String, CssProperties>,
): List<XhtmlSpan> {
    val spans = mutableListOf<XhtmlSpan>()
    for (node in nodes) {
        when (node) {
            is TextNode -> {
                val text = node.getWholeText()
                if (text.isNotBlank() || (text.isNotEmpty() && spans.isNotEmpty())) {
                    spans.add(XhtmlSpan.Text(text, style))
                }
            }
            is Element -> when (node.tagName().lowercase()) {
                "br" -> spans.add(XhtmlSpan.LineBreak())
                "img", "image", "svg" -> Unit
                else -> {
                    val childStyle = applyElementStyle(node, style, styles)
                    spans.addAll(childNodesToSpans(node.childNodes(), childStyle, styles))
                }
            }
        }
    }
    return spans
}

private fun flattenToSpans(
    element: Element,
    parentStyle: InlineStyle,
    styles: Map<String, CssProperties>,
): List<XhtmlSpan> {
    val spans = mutableListOf<XhtmlSpan>()
    val style = applyElementStyle(element, parentStyle, styles)
    for (node in element.childNodes()) {
        when (node) {
            is TextNode -> {
                val text = node.getWholeText()
                if (text.isNotEmpty()) spans.add(XhtmlSpan.Text(text, style))
            }
            is Element -> {
                if (node.tagName().equals("br", ignoreCase = true)) {
                    spans.add(XhtmlSpan.LineBreak())
                } else {
                    spans.addAll(flattenToSpans(node, style, styles))
                }
            }
        }
    }
    return spans
}

private fun applyElementStyle(
    element: Element,
    parentStyle: InlineStyle,
    styles: Map<String, CssProperties>,
): InlineStyle {
    var style = parentStyle
    when (element.tagName().lowercase()) {
        "b", "strong" -> style = style.copy(bold = true)
        "i", "em", "cite" -> style = style.copy(italic = true)
        "u" -> style = style.copy(underline = true)
        "s", "del", "strike" -> style = style.copy(strikethrough = true)
        "code", "tt", "kbd", "samp" -> style = style.copy(monospace = true)
        "sup" -> style = style.copy(superscript = true)
        "sub" -> style = style.copy(subscript = true)
        "a" -> style = style.copy(link = element.attr("href").ifBlank { null })
        "small" -> style = style.copy(fontSize = 0.85f)
        "big" -> style = style.copy(fontSize = 1.2f)
    }
    val cssProps = lookupCss(element, styles)
    if (cssProps.bold) style = style.copy(bold = true)
    if (cssProps.italic) style = style.copy(italic = true)
    if (cssProps.underline) style = style.copy(underline = true)
    if (cssProps.strikethrough) style = style.copy(strikethrough = true)
    val inlineCss = element.attr("style")
    if (inlineCss.isNotBlank()) style = applyInlineCss(style, inlineCss)
    return style
}