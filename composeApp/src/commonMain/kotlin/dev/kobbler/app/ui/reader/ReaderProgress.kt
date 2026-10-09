package dev.kobbler.app.ui.reader

/** The band the final chapter must start below. The server flips a book to Finished at
 *  progress >= 95 (kobbler api/src/routes/admin/reader.rs), so if the last chapter's share
 *  of the text is smaller than that gap, merely opening it would report Finished. */
private const val LAST_CHAPTER_BAND_START = 94.0

/** Relative chapter sizes for [readingProgressPercent] and [chapterForProgressPercent].
 *  parseEpub already holds every chapter's markup in memory, so raw length is the only
 *  size proxy that costs nothing; block or word counts would mean running the XHTML
 *  parser over the whole book on open. Per-chapter boilerplate makes it a slight
 *  over-estimate for tiny chapters, which errs towards reporting less progress. */
internal fun chapterWeights(chapters: List<EpubChapter>): List<Int> =
    chapters.map { it.xhtml.length }

/** Percent band boundaries, one more than there are chapters: chapter i spans
 *  `[bands[i], bands[i + 1])` and `bands[size]` is always 100. Null when there is no book. */
private fun progressBands(chapterWeights: List<Int>): DoubleArray? {
    val n = chapterWeights.size
    if (n == 0) return null
    // A floor of 1 gives empty chapters a non-empty band, which keeps the mapping
    // strictly increasing and therefore invertible, and makes an all-empty book fall
    // back to equal weighting instead of dividing by zero.
    val weights = DoubleArray(n) { chapterWeights[it].coerceAtLeast(1).toDouble() }
    val total = weights.sum()
    val bands = DoubleArray(n + 1)
    for (i in 0 until n) bands[i + 1] = bands[i] + weights[i]
    for (i in 0..n) bands[i] = bands[i] / total * 100.0
    val lastStart = bands[n - 1]
    if (lastStart > LAST_CHAPTER_BAND_START) {
        val squeeze = LAST_CHAPTER_BAND_START / lastStart
        for (i in 0 until n) bands[i] *= squeeze
    }
    return bands
}

/** Whole-book position as a percentage, weighted by chapter length so a 40-page chapter
 *  counts for more than a 2-page epilogue, with scroll within the current chapter folded
 *  in so the number moves continuously. [maxScroll] of 0 means the chapter is unscrollable
 *  (or the viewport is not measured yet), which reads as sitting at its start. */
internal fun readingProgressPercent(
    chapterIndex: Int,
    chapterWeights: List<Int>,
    scrollOffset: Int,
    maxScroll: Int,
): Double {
    val bands = progressBands(chapterWeights) ?: return 0.0
    val chapter = chapterIndex.coerceIn(0, chapterWeights.lastIndex)
    val withinChapter =
        if (maxScroll <= 0) 0.0 else (scrollOffset.toDouble() / maxScroll).coerceIn(0.0, 1.0)
    val start = bands[chapter]
    val end = bands[chapter + 1]
    // Interpolated in this form rather than start + t * (end - start) so t of 0 and 1 land
    // exactly on the band edges; chapterForProgressPercent has to invert them bit for bit.
    return (start * (1.0 - withinChapter) + end * withinChapter).coerceIn(0.0, 100.0)
}

/** Inverse of [readingProgressPercent]: the chapter whose band contains [percent]. A
 *  percent sitting exactly on a boundary belongs to the chapter that starts there, so a
 *  reader who finished chapter i resumes at i + 1. */
internal fun chapterForProgressPercent(percent: Double, chapterWeights: List<Int>): Int {
    val bands = progressBands(chapterWeights) ?: return 0
    val clamped = percent.coerceIn(0.0, 100.0)
    var chapter = 0
    for (i in chapterWeights.indices) if (bands[i] <= clamped) chapter = i
    return chapter
}
