package dev.kobbler.app.data

import kotlin.test.Test
import kotlin.test.assertEquals

class EpisodeLabelTest {

    private data class Case(
        val name: String,
        val identifier: String,
        val episodeTitle: String?,
        val expected: String,
    )

    private val cases = listOf(
        Case("sports round + session slug", "evt-r12-race", "Great Britain", "R12 Race · Great Britain"),
        Case("sports multi-word session", "evt-r5-free-practice-1", "British Grand Prix", "R5 Free Practice 1 · British Grand Prix"),
        Case("sports slug without a title", "evt-r12-race", null, "R12 Race"),
        Case("opaque provider-id slug drops entirely", "evt-2403662", "Motul Hungarian Round Race 1", "Motul Hungarian Round Race 1"),
        Case("opaque provider-id slug with no title", "evt-ev-567724", null, ""),
        Case("blank title is not appended", "evt-r12-race", "   ", "R12 Race"),
        Case("tv identifier passes through", "S01E02", "Pilot", "S01E02 · Pilot"),
        Case("tv identifier without a title", "S03E10", null, "S03E10"),
        Case("hyphenated title is untouched", "S02E04", "Spider-Man - No Way Home", "S02E04 · Spider-Man - No Way Home"),
        Case("title starting with evt is untouched", "S01E01", "evt-like naming in a real title", "S01E01 · evt-like naming in a real title"),
        Case("empty identifier yields the title alone", "", "Great Britain", "Great Britain"),
        Case("empty identifier and no title", "", null, ""),
    )

    @Test
    fun formatsLabels() {
        cases.forEach { c ->
            assertEquals(c.expected, formatEpisodeLabel(c.identifier, c.episodeTitle), c.name)
        }
    }
}
