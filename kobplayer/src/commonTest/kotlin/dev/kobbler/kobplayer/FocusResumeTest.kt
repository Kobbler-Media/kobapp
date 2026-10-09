package dev.kobbler.kobplayer

import kotlin.test.Test
import kotlin.test.assertEquals

class FocusResumeTest {

    private data class Case(
        val name: String,
        val steps: List<Pair<FocusChange, Boolean>>,
        val expected: List<FocusAction>,
        val viewerActsAfter: Int = -1,
    )

    @Test
    fun resumesOnlyWhatTheInterruptionPaused() {
        listOf(
            Case(
                "call while playing resumes after",
                listOf(FocusChange.LossTransient to true, FocusChange.Gain to false),
                listOf(FocusAction.Pause, FocusAction.Resume),
            ),
            Case(
                "call while paused stays paused",
                listOf(FocusChange.LossTransient to false, FocusChange.Gain to false),
                listOf(FocusAction.None, FocusAction.Restore),
            ),
            Case(
                "permanent loss never resumes",
                listOf(FocusChange.Loss to true, FocusChange.Gain to false),
                listOf(FocusAction.Pause, FocusAction.Restore),
            ),
            Case(
                "permanent loss while paused leaves it alone",
                listOf(FocusChange.Loss to false),
                listOf(FocusAction.None),
            ),
            Case(
                "viewer touching transport during the call cancels the resume",
                listOf(FocusChange.LossTransient to true, FocusChange.Gain to false),
                listOf(FocusAction.Pause, FocusAction.Restore),
                viewerActsAfter = 0,
            ),
            Case(
                "already playing again on gain is left alone",
                listOf(FocusChange.LossTransient to true, FocusChange.Gain to true),
                listOf(FocusAction.Pause, FocusAction.Restore),
            ),
            Case(
                "ducks while playing",
                listOf(FocusChange.LossTransientCanDuck to true, FocusChange.Gain to true),
                listOf(FocusAction.Duck, FocusAction.Restore),
            ),
        ).forEach { c -> assertEquals(c.expected, run(FocusResume(), c), c.name) }
    }

    @Test
    fun speechPausesWhereVideoWouldDuck() {
        val c = Case(
            "speech",
            listOf(FocusChange.LossTransientCanDuck to true, FocusChange.Gain to false),
            listOf(FocusAction.Pause, FocusAction.Resume),
        )
        assertEquals(c.expected, run(FocusResume(canDuck = false), c))
    }

    private fun run(policy: FocusResume, c: Case) = c.steps.mapIndexed { i, (change, playing) ->
        policy.onChange(change, playing).also { if (i == c.viewerActsAfter) policy.forget() }
    }
}
