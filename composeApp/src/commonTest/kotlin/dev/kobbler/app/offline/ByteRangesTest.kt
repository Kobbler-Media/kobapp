package dev.kobbler.app.offline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ByteRangesTest {
    private fun ranges(vararg pairs: Pair<Long, Long>) =
        pairs.fold(ByteRanges()) { acc, (s, e) -> acc + (s until e) }

    @Test
    fun overlappingAndTouchingSpansMerge() {
        val r = ranges(0L to 10L, 10L to 20L, 15L to 30L, 40L to 50L)
        assertEquals(listOf(0L until 30L, 40L until 50L), r.spans)
        assertEquals(40L, r.filledBytes)
    }

    @Test
    fun aSpanSwallowingSeveralCollapsesThem() {
        val r = ranges(0L to 5L, 10L to 15L, 20L to 25L, 2L to 22L)
        assertEquals(listOf(0L until 25L), r.spans)
    }

    @Test
    fun filledRunFromAnOffset() {
        val r = ranges(10L to 20L)
        assertEquals(20L, r.filledEndFrom(10))
        assertEquals(20L, r.filledEndFrom(15))
        assertNull(r.filledEndFrom(20))
        assertNull(r.filledEndFrom(5))
    }

    @Test
    fun gapEndsAtTheNextFilledSpanOrTheFile() {
        val r = ranges(10L to 20L, 30L to 40L)
        assertEquals(10L, r.gapEndFrom(0, size = 100))
        assertEquals(30L, r.gapEndFrom(20, size = 100))
        assertEquals(100L, r.gapEndFrom(40, size = 100))
    }

    @Test
    fun firstGapPrefersThePlayheadThenWraps() {
        val r = ranges(0L to 10L, 50L to 100L)
        assertEquals(10L until 50L, r.firstGap(from = 0, size = 100))
        assertEquals(30L until 50L, r.firstGap(from = 30, size = 100))
        assertEquals(10L until 50L, r.firstGap(from = 60, size = 100))
        assertNull(ranges(0L to 100L).firstGap(from = 0, size = 100))
    }

    @Test
    fun completeOnlyWhenTheWholeFileIsCovered() {
        assertTrue(ranges(0L to 100L).covers(100))
        assertFalse(ranges(0L to 99L).covers(100))
        assertFalse(ByteRanges().covers(1))
    }

    @Test
    fun progressCountsFinishedFilesUntilEveryFileIsSized() {
        val item = OfflineItem(
            OfflineKind.Audiobook, "b", "Book",
            files = listOf(OfflineFile("a", size = 10, ranges = ByteRanges() + (0L until 10L)), OfflineFile("b")),
        )
        assertEquals(0.5f, item.progress)
    }
}
