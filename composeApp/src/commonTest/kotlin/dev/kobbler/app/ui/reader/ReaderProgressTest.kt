package dev.kobbler.app.ui.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReaderProgressTest {

    private fun uniform(count: Int, size: Int = 10_000) = List(count) { size }

    private data class Case(
        val name: String,
        val chapterIndex: Int,
        val weights: List<Int>,
        val scrollOffset: Int,
        val maxScroll: Int,
        val expected: Double,
    )

    private val cases = listOf(
        Case("empty book", 0, emptyList(), 0, 0, 0.0),
        Case("single chapter at top", 0, uniform(1), 0, 0, 0.0),
        Case("single chapter half scrolled", 0, uniform(1), 600, 1200, 50.0),
        Case("single chapter fully scrolled", 0, uniform(1), 1200, 1200, 100.0),
        Case("first chapter of twenty at top", 0, uniform(20), 0, 1200, 0.0),
        Case("first chapter of twenty half read", 0, uniform(20), 600, 1200, 2.4736842105263159),
        Case("nineteenth chapter of twenty at top", 18, uniform(20), 0, 1200, 89.052631578947370),
        Case("last chapter of twenty at top", 19, uniform(20), 0, 1200, 94.0),
        Case("last chapter of twenty fully read", 19, uniform(20), 1200, 1200, 100.0),
        Case("unscrollable chapter sits at its start", 5, uniform(10), 0, 0, 50.0),
        Case("scroll past max clamps to chapter end", 1, uniform(4), 5000, 1200, 50.0),
        Case("negative scroll clamps to chapter start", 1, uniform(4), -50, 1200, 25.0),
        Case("chapter index past the end clamps", 40, uniform(20), 1200, 1200, 100.0),
        Case("long chapter after a short one", 1, listOf(1_000, 9_000), 4_500, 9_000, 55.0),
        Case("short chapter after a long one", 1, listOf(9_000, 1_000), 0, 1200, 90.0),
        Case("front matter barely counts", 1, listOf(200, 100_000), 0, 1200, 0.19960079840319361),
    )

    @Test
    fun tableOfReadingPositions() {
        val failures = cases.mapNotNull { c ->
            val actual = readingProgressPercent(c.chapterIndex, c.weights, c.scrollOffset, c.maxScroll)
            if (kotlin.math.abs(actual - c.expected) < 1e-9) null
            else "${c.name}: expected ${c.expected}, got $actual"
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun openingTheLastChapterOfTwentyIsNotFinished() {
        val pct = readingProgressPercent(chapterIndex = 19, chapterWeights = uniform(20), scrollOffset = 0, maxScroll = 1200)
        assertTrue(pct < 95.0, "reported $pct, which trips the server's >=95 Finished threshold")
    }

    @Test
    fun openingTheLastChapterIsNeverFinishedAtAnyBookLength() {
        val shapes = listOf(
            uniform(2),
            uniform(20),
            uniform(200),
            List(30) { 10_000 } + listOf(50),
            List(12) { it * 4_000 + 1 },
            List(8) { 0 },
        )
        val failures = shapes.mapNotNull { w ->
            val pct = readingProgressPercent(w.lastIndex, w, 0, 1200)
            if (pct < 95.0) null else "${w.size} chapters ending ${w.last()}: reported $pct"
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun theGenuineEndOfTheBookIsAHundred() {
        val shapes = listOf(uniform(1), uniform(20), listOf(200, 100_000), List(8) { 0 })
        val failures = shapes.mapNotNull { w ->
            val pct = readingProgressPercent(w.lastIndex, w, 1200, 1200)
            if (pct == 100.0) null else "${w.size} chapters: reported $pct"
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun oneChapterCarryingMostOfTheBookDominatesTheRange() {
        val weights = listOf(2_000, 180_000, 2_000, 2_000)
        val chapterTwoStart = readingProgressPercent(1, weights, 0, 1200)
        val chapterTwoEnd = readingProgressPercent(2, weights, 0, 1200)
        assertTrue(chapterTwoEnd - chapterTwoStart > 85.0, "the 90% chapter spanned only ${chapterTwoEnd - chapterTwoStart}")
    }

    @Test
    fun progressIsMonotonicAcrossAChapter() {
        val samples = (0..1200 step 100).map {
            readingProgressPercent(chapterIndex = 3, chapterWeights = uniform(10), scrollOffset = it, maxScroll = 1200)
        }
        assertEquals(samples.sorted(), samples)
        assertEquals(30.0, samples.first())
        assertEquals(40.0, samples.last())
    }

    @Test
    fun progressIsMonotonicAcrossTheWholeBook() {
        val weights = listOf(400, 62_000, 41_000, 0, 88_000, 1_200, 55_000, 300)
        val samples = weights.indices.flatMap { ch ->
            (0..1200 step 300).map { readingProgressPercent(ch, weights, it, 1200) }
        }
        assertEquals(samples.sorted(), samples)
        assertEquals(0.0, samples.first())
        assertEquals(100.0, samples.last())
    }

    private val roundTripShapes = listOf(
        uniform(1),
        uniform(20),
        uniform(200),
        listOf(200, 100_000),
        listOf(2_000, 180_000, 2_000, 2_000),
        listOf(400, 62_000, 41_000, 0, 88_000, 1_200, 55_000, 300),
        List(8) { 0 },
        List(30) { 10_000 } + listOf(50),
    )

    @Test
    fun percentRoundTripsBackToTheSameChapter() {
        val failures = roundTripShapes.flatMap { weights ->
            weights.indices.flatMap { ch ->
                listOf(0, 300, 600, 900, 1199).mapNotNull { offset ->
                    val pct = readingProgressPercent(ch, weights, offset, 1200)
                    val restored = chapterForProgressPercent(pct, weights)
                    if (restored == ch) null
                    else "${weights.size} chapters, chapter $ch at offset $offset -> $pct -> $restored"
                }
            }
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun restoreClampsOutOfRangePercents() {
        val weights = uniform(20)
        assertEquals(0, chapterForProgressPercent(-10.0, weights))
        assertEquals(0, chapterForProgressPercent(0.0, weights))
        assertEquals(19, chapterForProgressPercent(100.0, weights))
        assertEquals(19, chapterForProgressPercent(250.0, weights))
        assertEquals(0, chapterForProgressPercent(50.0, emptyList()))
    }

    @Test
    fun finishingAChapterRestoresToTheNextOne() {
        val weights = uniform(20)
        val endOfChapterFour = readingProgressPercent(3, weights, 1200, 1200)
        assertEquals(4, chapterForProgressPercent(endOfChapterFour, weights))
    }

    @Test
    fun weightsComeFromChapterMarkupLength() {
        val chapters = listOf(
            EpubChapter(title = "a", href = "a.xhtml", xhtml = "<p>hello</p>"),
            EpubChapter(title = "b", href = "b.xhtml", xhtml = ""),
        )
        assertEquals(listOf(12, 0), chapterWeights(chapters))
    }
}
