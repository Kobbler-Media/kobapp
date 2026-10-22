package dev.kobbler.app.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals

/** A buffer whose live edge stops moving is dead, and timeshift must not sit on it. */
class BufferStallWatchTest {

    private fun stalledAt(edges: List<Pair<Long, Double>>): List<Boolean> {
        val watch = BufferStallWatch(stallMs = 20_000)
        return edges.map { (now, edge) -> watch.observe(edge, now) }
    }

    @Test
    fun `a growing buffer is never stalled`() {
        assertEquals(List(6) { false }, stalledAt((0..5).map { it * 5_000L to it * 5.0 }))
    }

    @Test
    fun `an edge that stops for the whole window is stalled`() {
        val edges = listOf(0L to 100.0, 5_000L to 105.0, 10_000L to 105.0, 20_000L to 105.0, 25_000L to 105.0, 26_000L to 105.0)
        assertEquals(listOf(false, false, false, false, true, true), stalledAt(edges))
    }

    @Test
    fun `growth again clears it`() {
        val edges = listOf(0L to 100.0, 25_000L to 100.0, 30_000L to 101.0, 31_000L to 101.0)
        assertEquals(listOf(false, true, false, false), stalledAt(edges))
    }
}
