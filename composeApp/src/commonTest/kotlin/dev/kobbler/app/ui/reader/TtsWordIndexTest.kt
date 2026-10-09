package dev.kobbler.app.ui.reader

import dev.kobbler.app.data.TtsWordTiming
import kotlin.test.Test
import kotlin.test.assertEquals

class TtsWordIndexTest {

    private fun word(i: Int, t0: Int, t1: Int) = TtsWordTiming(wordIndex = i, text = "w$i", t0Ms = t0, t1Ms = t1)

    /** Silences are wide enough to clear the old 150ms trailing-overshoot allowance. */
    private val alignments = listOf(
        ParagraphAlignment(
            wordOffset = 0,
            audioOffsetMs = 0,
            words = listOf(word(0, 0, 300), word(1, 800, 1100)),
        ),
        ParagraphAlignment(
            wordOffset = 2,
            audioOffsetMs = 2000,
            words = listOf(word(0, 0, 300), word(1, 800, 1100)),
        ),
    )

    @Test
    fun resolvesWordsAndParagraphOffsets() {
        val cases = listOf(
            100L to 0,
            900L to 1,
            2100L to 2,
            2900L to 3,
        )
        cases.forEach { (posMs, expected) ->
            assertEquals(expected, resolveWordIndex(alignments, posMs), "posMs=$posMs")
        }
    }

    @Test
    fun noAlignmentBeforeFirstParagraph() {
        assertEquals(-1, resolveWordIndex(alignments, -5L))
    }

    /**
     * The page-bounce regression: during inter-word silence and across paragraph
     * boundaries the resolver has no word, and publishing that as -1 flipped the
     * renderer's active block between the spoken word and a fuzzy paragraph match.
     * The last spoken word must stay put until the next real one arrives.
     */
    @Test
    fun highlightStaysOnLastWordThroughSilence() {
        val cases = listOf(
            600L to 0,   // gap between word 0 and word 1 of the first paragraph
            1500L to 1,  // trailing silence after the first paragraph's last word
            1800L to 1,  // gap between the two paragraphs
            3500L to 3,  // trailing silence at the end
        )
        cases.forEach { (posMs, expected) ->
            val current = expected - 1
            assertEquals(expected, stickyWordIndex(current, alignments, posMs), "posMs=$posMs")
        }
    }

    @Test
    fun stickyIndexStillReportsNothingBeforePlaybackStarts() {
        assertEquals(-1, stickyWordIndex(-1, alignments, -5L))
    }

    /**
     * The failure this exists for: alignment lands a chunk at a time and the
     * voice runs ahead of it. The scan sticks to the last word it knows, so
     * without the anchors the highlight pegged itself to the aligner's frontier
     * and the reader sat on a page the listener had left minutes ago.
     */
    @Test
    fun beyondWhatTheAlignerHasResolvedTheAnchorsCarryTheHighlight() {
        val p = listOf(
            ParagraphAlignment(
                wordOffset = 0,
                audioOffsetMs = 0,
                // Aligned only as far as word 1, which ends at 1100 ms.
                words = listOf(word(0, 0, 300), word(1, 800, 1100)),
                // Anchored across all four, a second each.
                anchorWords = listOf(word(0, 0, 1000), word(1, 1000, 2000),
                                     word(2, 2000, 3000), word(3, 3000, 4000)),
            ),
        )
        // Inside the aligned prefix the real timings win: at 900 ms the anchors
        // would say word 0, the aligner says word 1, and the aligner is right.
        assertEquals(1, resolveWordIndex(p, 900L))
        // Past it, the anchors carry on rather than the highlight stalling on 1.
        assertEquals(2, resolveWordIndex(p, 2500L))
        assertEquals(3, resolveWordIndex(p, 3500L))
    }

    /** With no anchors, sticking to the last aligned word is still right. */
    @Test
    fun withoutAnchorsTheLastAlignedWordStillSticks() {
        assertEquals(3, resolveWordIndex(alignments, 9999L))
    }
}
