package dev.kobbler.app.ui.reader

import dev.kobbler.app.ui.reader.pack.Block
import dev.kobbler.app.ui.reader.pack.BlockKind

data class TtsWordHighlight(val blockIndex: Int, val charRange: IntRange)

/** One backend-visible word of a chapter. [globalIndex] matches the forced aligner's
 *  index, so it must count every word the backend receives, tables and fixed pages
 *  included. [chapterCharOffset] is where the word starts in [ChapterTtsView.plainText].
 *  [highlightCharRange] indexes the block's text, null when the block has no single text
 *  layout to paint it on (tables, fixed-page fragments). */
data class TtsWord(
    val globalIndex: Int,
    val chapterCharOffset: Int,
    val blockIndex: Int,
    val highlightCharRange: IntRange?,
)

data class ChapterTtsView(val plainText: String, val words: List<TtsWord>)

/**
 * Build the exact text sent to the TTS backend plus the word table used to map the
 * forced aligner's global word indices back to on-screen char ranges. Both come out of
 * one tokenizer pass — if the payload and the table ever disagree on what counts as a
 * word, every highlight after the disagreement drifts.
 *
 * Emission rules (must match what the backend is fed):
 *  - Text / Heading: the text as written. Punctuation is the author's — a fixed-layout
 *    book puts every VISUAL LINE in its own block, and inventing a full stop at the end
 *    of each one turns a wrapped sentence into six sentences. The server rejoins those
 *    wraps before synthesis, but only while they still read as wraps.
 *  - Table cells and fixed-page fragments: joined as their own paragraphs. Their words
 *    count towards the global index but carry no highlight range.
 *  - Code / Image / Rule: not spoken.
 *  - Blocks separated by "\n\n", which is the only break the server treats as a
 *    candidate paragraph boundary.
 */
fun buildChapterTtsView(blocks: List<Block>): ChapterTtsView {
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
        // measured in the trimmed payload need the trimmed prefix added back.
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
        when (block.kind) {
            BlockKind.Text, BlockKind.Heading -> emitBlock(block.text, idx, highlightAllowed = true)
            BlockKind.Table, BlockKind.FixedPage -> emitBlock(blockPlainText(block, "\n\n"), idx, highlightAllowed = false)
            BlockKind.Code, BlockKind.Image, BlockKind.Rule -> {}
        }
    }

    return ChapterTtsView(plain.toString(), words)
}

/**
 * Spaces the aligner counts as separators but Kotlin does not.
 *
 * `Char.isWhitespace` is false for a no-break space, so "M. Dupont" tokenizes here
 * as one word and on the server — which flattens them before synthesis — as two. Every
 * word index after the first one in a chapter would refer to a different word.
 * Length-preserving, so highlight ranges still index the rendered text.
 */
private val HardSpaces = charArrayOf(' ', ' ', ' ')

private fun flattenHardSpaces(text: String): String =
    if (text.none { it in HardSpaces }) text
    else text.map { if (it in HardSpaces) ' ' else it }.joinToString("")

internal fun blockPlainText(block: Block, separator: String = " "): String = when (block.kind) {
    BlockKind.Text, BlockKind.Heading, BlockKind.Code -> block.text
    BlockKind.Table -> block.table?.rows.orEmpty()
        .flatMap { row -> row.cells.flatMap { cell -> cell.blocks.map { it.text.trim() } } }
        .filter { it.isNotEmpty() }
        .joinToString(separator)
    BlockKind.FixedPage -> block.fixed?.fragments.orEmpty()
        .map { fragment -> fragment.spans.joinToString("") { it.text }.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(separator)
    BlockKind.Image, BlockKind.Rule -> ""
}

fun findActiveBlockIndex(blocks: List<Block>, highlighted: String?): Int {
    val needle = normalizeNeedle(highlighted) ?: return -1
    return blocks.indexOfFirst { block ->
        val text = blockPlainText(block)
        text.isNotEmpty() && normalizeForMatch(text).contains(needle)
    }
}

// Common typographic chars are normalised to ASCII so EPUB curly quotes / em-dashes
// match TTS output that often round-trips through ASCII.
private fun normalizeChar(c: Char): Char = when (c) {
    '‘', '’', '‚', '‛' -> '\''
    '“', '”', '„', '‟' -> '"'
    '–', '—' -> '-'
    ' ' -> ' '
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
internal fun fuzzyFind(haystack: String, needle: String?): IntRange? {
    if (needle.isNullOrBlank()) return null
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
