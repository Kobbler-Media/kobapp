package dev.kobbler.kobplayer

import kotlin.test.Test
import kotlin.test.assertEquals

class SeekPlanTest {

    private data class Case(
        val name: String,
        val targetMs: Long,
        val offsetMs: Long,
        val producedEndMs: Long,
        val canReopen: Boolean,
        val expected: SeekPlan,
    )

    @Test
    fun `seeks land locally inside what was produced and reopen outside it`() {
        listOf(
            Case("inside, no offset", 30_000, 0, 60_000, true, SeekPlan.Local(30_000)),
            Case("inside, after a reopen", 130_000, 100_000, 60_000, true, SeekPlan.Local(30_000)),
            Case("the produced edge itself", 60_000, 0, 60_000, true, SeekPlan.Local(60_000)),
            Case("past the produced edge", 600_000, 0, 60_000, true, SeekPlan.Reopen(600_000)),
            Case("before a reopened start", 50_000, 100_000, 60_000, true, SeekPlan.Reopen(50_000)),
            Case("past the edge but nothing to reopen with", 600_000, 0, 60_000, false, SeekPlan.Local(600_000)),
            Case("before the start with nothing to reopen with", 50_000, 100_000, 60_000, false, SeekPlan.Local(0)),
        ).forEach { case ->
            assertEquals(
                case.expected,
                planSeek(case.targetMs, case.offsetMs, case.producedEndMs, case.canReopen),
                case.name,
            )
        }
    }
}
