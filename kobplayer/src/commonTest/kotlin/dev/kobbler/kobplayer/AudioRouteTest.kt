package dev.kobbler.kobplayer

import kotlin.test.Test
import kotlin.test.assertEquals

class AudioRouteTest {

    private val dolby = "encoded:${encodingMask(ENCODING_AC3, ENCODING_E_AC3)}"

    private sealed interface Step {
        data class Select(val device: String, val handed: List<String>) : Step
        data object Invalidated : Step
        data object Reset : Step
    }

    private data class Case(val name: String, val steps: List<Step>)

    @Test
    fun handsLibVlcWhatRebuildsTheOutput() {
        listOf(
            Case("first choice", listOf(Step.Select("stereo", listOf("stereo")))),
            Case("unchanged choice is left alone", listOf(
                Step.Select("stereo", listOf("stereo")),
                Step.Select("stereo", emptyList()),
            )),
            Case("new choice", listOf(
                Step.Select("stereo", listOf("stereo")),
                Step.Select(dolby, listOf(dolby)),
            )),
            // TV off and on behind a stereo route: the device id never changes, so only a
            // detour gets libVLC to drop the AudioTrack that died with the sink.
            Case("same choice once invalidated is rebuilt", listOf(
                Step.Select("stereo", listOf("stereo")),
                Step.Invalidated,
                Step.Select("stereo", listOf("pcm", "stereo")),
                Step.Select("stereo", emptyList()),
            )),
            Case("bitstream rebuilt through stereo", listOf(
                Step.Select(dolby, listOf(dolby)),
                Step.Invalidated,
                Step.Select(dolby, listOf("stereo", dolby)),
            )),
            Case("new choice once invalidated needs no detour", listOf(
                Step.Select("stereo", listOf("stereo")),
                Step.Invalidated,
                Step.Select(dolby, listOf(dolby)),
            )),
            Case("sinks changing before any output has nothing to rebuild", listOf(
                Step.Invalidated,
                Step.Select("stereo", listOf("stereo")),
            )),
            Case("a fresh player is handed its device again", listOf(
                Step.Select("stereo", listOf("stereo")),
                Step.Reset,
                Step.Select("stereo", listOf("stereo")),
            )),
        ).forEach { c ->
            val route = AudioRoute()
            c.steps.forEachIndexed { i, step ->
                when (step) {
                    is Step.Select -> assertEquals(step.handed, route.select(step.device), "${c.name}, step $i")
                    Step.Invalidated -> assertEquals(i > 0, route.invalidate(), "${c.name}, step $i")
                    Step.Reset -> route.reset()
                }
            }
        }
    }
}
