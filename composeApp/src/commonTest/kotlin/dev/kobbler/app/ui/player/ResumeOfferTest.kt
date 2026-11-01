package dev.kobbler.app.ui.player

import dev.kobbler.app.data.PositionResponse
import dev.kobbler.app.data.SkipSegment
import kotlin.test.Test
import kotlin.test.assertEquals

class ResumeOfferTest {

    private val credits = listOf(SkipSegment(segmentType = "credits", startSeconds = 3131.0, endSeconds = 3311.0))
    private val duration = 3311.0

    @Test
    fun `a title finished short of its ending offers its saved position`() {
        data class Case(
            val name: String,
            val state: PositionResponse,
            val segments: List<SkipSegment>,
            val expected: Int?,
        )
        val cases = listOf(
            Case("finished by the 90% rule before the credits", PositionResponse(2990, watched = true), credits, 2990),
            Case("finished inside the credits", PositionResponse(3140, watched = true), credits, null),
            Case("finished by playing to the end", PositionResponse(3311, watched = true), credits, null),
            Case("no credits, well short of the end", PositionResponse(2990, watched = true), emptyList(), 2990),
            Case("no credits, at the very end", PositionResponse(3300, watched = true), emptyList(), null),
            Case("marked watched by hand, no position", PositionResponse(null, watched = true), credits, null),
            Case("not watched — the normal resume handles it", PositionResponse(2990, watched = false), credits, null),
            Case("intro segments are not an ending", PositionResponse(2990, watched = true),
                listOf(SkipSegment(segmentType = "intro", startSeconds = 60.0, endSeconds = 90.0)), 2990),
        )
        for (c in cases) {
            assertEquals(c.expected, earlyFinishSeconds(c.state, c.segments, duration), c.name)
        }
    }
}
