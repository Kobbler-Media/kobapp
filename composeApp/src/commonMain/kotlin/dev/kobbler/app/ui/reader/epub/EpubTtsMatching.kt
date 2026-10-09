package dev.kobbler.app.ui.reader.epub

/**
 * Build the exact text sent to the TTS backend plus the word table used to map the
 * forced aligner's global word indices back to on-screen char ranges. Both come out of
 * one tokenizer pass — if the payload and the table ever disagree on what counts as a
 * word, every highlight after the disagreement drifts.
 *
 * Emission rules (must match what the backend is fed):
 *  - Paragraph / Heading: the text as written. Punctuation is the author's — a fixed-
 *    layout book puts every VISUAL LINE in its own block, and inventing a full stop at
 *    the end of each one turns a wrapped sentence into six sentences. The server rejoins
 *    those wraps before synthesis, but only while they still read as wraps.
 *  - ListBlock / BlockQuote: children joined as their own paragraphs. Their words count
 *    towards the global index but carry no highlight range — the renderer has no single
 *    Text composable to style.
 *  - Divider / Spacer / Image / CodeBlock: not spoken.
 *  - Blocks separated by "\n\n", which is the only break the server treats as a
 *    candidate paragraph boundary.
 */
fun buildChapterTtsView(blocks: List<XhtmlBlock>): ChapterTtsView {
    val plain = StringBuilder()
    val words = ArrayList<TtsWord>()
    var globalIdx = 0

    fun emitBlock(blockText: String, blockIdx: Int, highlightAllowed: Boolean) {
        val rawText = flattenHardSpaces(blockText)
        val emit = rawText.trim()
        if (emit.isEmpty()) return
        if (plain.isNotEmpty()) plain.append("\n\n")
        val chapterBase = plain.length
        plain.append(emit)

        // Highlight ranges index rawText (the renderer's own text), so token offsets
        // measured in the trimmed+terminated payload need the trimmed prefix added back.
        val leadingWs = rawText.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        var i = 0
        while (i < emit.length) {
            while (i < emit.length && emit[i].isWhitespace()) i++
            if (i >= emit.length) break
            val tokStart = i
            while (i < emit.length && !emit[i].isWhitespace()) i++
            val tokEnd = i - 1
            val rangeInBlock = if (highlightAllowed) {
                val rawStart = leadingWs + tokStart
                val rawEnd = (leadingWs + tokEnd).coerceAtMost(rawText.length - 1)
                if (rawEnd >= rawStart) rawStart..rawEnd else null
            } else null
            words.add(TtsWord(globalIdx, chapterBase + tokStart, blockIdx, rangeInBlock))
            globalIdx++
        }
    }

    for ((idx, block) in blocks.withIndex()) {
        when (block) {
            is XhtmlBlock.Paragraph -> emitBlock(spanText(block.spans), idx, highlightAllowed = true)
            is XhtmlBlock.Heading -> emitBlock(spanText(block.spans), idx, highlightAllowed = true)
            is XhtmlBlock.ListBlock -> emitBlock(
                block.items.joinToString("\n\n") { spanText(it.spans).trim() },
                idx,
                highlightAllowed = false,
            )
            is XhtmlBlock.BlockQuote -> emitBlock(
                block.blocks.filterIsInstance<XhtmlBlock.Paragraph>()
                    .joinToString("\n\n") { spanText(it.spans).trim() },
                idx,
                highlightAllowed = false,
            )
            is XhtmlBlock.FixedPage -> emitBlock(
                block.fragments.joinToString("\n\n") { spanText(it.spans).trim() },
                idx,
                // Each fragment is its own Text composable, so a word range cannot be
                // mapped to one char range — sentence-level nav still works via
                // findActiveBlockIndex on the joined text.
                highlightAllowed = false,
            )
            is XhtmlBlock.CodeBlock, is XhtmlBlock.Image, XhtmlBlock.Divider, XhtmlBlock.Spacer -> {}
        }
    }

    return ChapterTtsView(plain.toString(), words)
}

/**
 * Spaces the aligner counts as separators but Kotlin does not.
 *
 * `Char.isWhitespace` is false for a no-break space, so "M.\u00A0Dupont" tokenizes here
 * as one word and on the server — which flattens them before synthesis — as two. Every
 * word index after the first one in a chapter would refer to a different word.
 * Length-preserving, so highlight ranges still index the rendered text.
 */
private val HardSpaces = charArrayOf('\u00A0', '\u2007', '\u202F')

private fun flattenHardSpaces(text: String): String =
    if (text.none { it in HardSpaces }) text
    else text.map { if (it in HardSpaces) ' ' else it }.joinToString("")

/**
 * The block's text, as the RENDERER lays it out.
 *
 * A `<br/>` must come out as the "\n" [buildStyledText] appends for it, for two
 * separate reasons. The highlight ranges built here index this string and are
 * applied to that one, so dropping the break shifts every word after it one
 * character left per `<br/>` — the highlight slides off the word and straddles
 * two. And a dropped break also glues the words either side of it into one
 * token ("Chapter OneThe Beginning"), which the engine then reads as one word
 * and the aligner counts as one. Verse, epigraphs, dedications and title pages
 * are where `<br/>` lives, so this is worst at the front of a book.
 */
private fun spanText(spans: List<XhtmlSpan>): String = buildString {
    for (span in spans) when (span) {
        is XhtmlSpan.Text -> append(span.text)
        is XhtmlSpan.LineBreak -> append("\n")
    }
}

internal fun blockPlainText(block: XhtmlBlock): String = when (block) {
    is XhtmlBlock.Paragraph -> spanText(block.spans)
    is XhtmlBlock.Heading -> spanText(block.spans)
    is XhtmlBlock.BlockQuote -> block.blocks.joinToString(" ") { blockPlainText(it) }
    is XhtmlBlock.ListBlock -> block.items.joinToString(" ") { item -> spanText(item.spans) }
    is XhtmlBlock.CodeBlock -> block.text
    is XhtmlBlock.FixedPage -> block.fragments.joinToString(" ") { spanText(it.spans) }
    is XhtmlBlock.Image, XhtmlBlock.Divider, XhtmlBlock.Spacer -> ""
}

fun findActiveBlockIndex(blocks: List<XhtmlBlock>, highlighted: String?): Int {
    val needle = normalizeNeedle(highlighted) ?: return -1
    return blocks.indexOfFirst { block ->
        val text = blockPlainText(block)
        text.isNotEmpty() && normalizeForMatch(text).contains(needle)
    }
}

fun findActiveListItemIndex(items: List<ListItem>, highlighted: String?): Int {
    val needle = normalizeNeedle(highlighted) ?: return -1
    return items.indexOfFirst { item ->
        val text = item.spans.filterIsInstance<XhtmlSpan.Text>().joinToString("") { it.text }
        text.isNotEmpty() && normalizeForMatch(text).contains(needle)
    }
}

fun findHighlightRange(spans: List<XhtmlSpan>, highlighted: String?): IntRange? {
    if (highlighted.isNullOrEmpty()) return null
    val joined = buildString {
        for (span in spans) when (span) {
            is XhtmlSpan.Text -> append(span.text)
            is XhtmlSpan.LineBreak -> append("\n")
        }
    }
    return fuzzyFind(joined, highlighted)
}

// Common typographic chars are normalised to ASCII so EPUB curly quotes / em-dashes
// match TTS output that often round-trips through ASCII.
private fun normalizeChar(c: Char): Char = when (c) {
    '‘', '’', '‚', '‛' -> '\''
    '“', '”', '„', '‟' -> '"'
    '–', '—' -> '-'
    ' ' -> ' '
    else -> c
}

internal fun normalizeForMatch(s: String): String = buildString(s.length) {
    var inWs = false
    for (c in s) {
        val nc = normalizeChar(c)
        if (nc.isWhitespace()) {
            if (isNotEmpty() && !inWs) { append(' '); inWs = true }
        } else {
            append(nc.lowercaseChar()); inWs = false
        }
    }
}.trimEnd()

private fun normalizeNeedle(s: String?): String? {
    if (s.isNullOrBlank()) return null
    val n = normalizeForMatch(s).trimEnd { it in ".!?;,:\"'" }
    return n.ifEmpty { null }
}

/**
 * Find [needle] in [haystack] with typographic + whitespace normalisation.
 * Returns range in the *original* haystack string. When the needle originally
 * ended with terminal punctuation, the returned range is extended to include
 * a matching trailing punctuation char in the haystack so the visual highlight
 * covers the whole sentence including its period.
 */
internal fun fuzzyFind(haystack: String, needle: String): IntRange? {
    if (needle.isBlank()) return null
    val rawNeedle = normalizeForMatch(needle)
    val normNeedle = rawNeedle.trimEnd { it in ".!?;,:\"'" }
    if (normNeedle.isEmpty()) return null
    val needleEndedInTerminator = rawNeedle.length > normNeedle.length

    val origPositions = ArrayList<Int>(haystack.length)
    val norm = buildString(haystack.length) {
        var inWs = false
        for (i in haystack.indices) {
            val nc = normalizeChar(haystack[i])
            if (nc.isWhitespace()) {
                if (isNotEmpty() && !inWs) { origPositions.add(i); append(' '); inWs = true }
            } else {
                origPositions.add(i); append(nc.lowercaseChar()); inWs = false
            }
        }
    }
    val idx = norm.indexOf(normNeedle)
    if (idx < 0) return null

    val start = origPositions[idx]
    val endNorm = (idx + normNeedle.length - 1).coerceAtMost(origPositions.lastIndex)
    var end = origPositions[endNorm]
    if (needleEndedInTerminator && end + 1 < haystack.length && haystack[end + 1] in ".!?;,:") {
        end++
    }
    return start..end
}
