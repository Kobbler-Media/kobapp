package dev.kobbler.kobplayer

import kotlin.test.Test
import kotlin.test.assertEquals

class LatestWinsTest {

    private class Worker {
        val queue = ArrayDeque<() -> Unit>()
        fun post(task: () -> Unit): Boolean = queue.add(task)
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst()() }
    }

    private fun slot(worker: Worker, ran: MutableList<Long>) = LatestWins<Long>(worker::post) { ran += it }

    @Test
    fun aBurstWhileTheWorkerIsBusyRunsOnlyTheNewest() {
        val worker = Worker()
        val ran = mutableListOf<Long>()
        val seeks = slot(worker, ran)
        listOf(10_000L, 20_000L, 30_000L, 40_000L).forEach(seeks::offer)
        worker.drain()
        assertEquals(listOf(40_000L), ran)
        assertEquals(0, worker.queue.size)
    }

    @Test
    fun anOfferAfterTheWorkerTookThePreviousOneRunsToo() {
        val worker = Worker()
        val ran = mutableListOf<Long>()
        val seeks = slot(worker, ran)
        seeks.offer(10_000L)
        worker.drain()
        seeks.offer(20_000L)
        worker.drain()
        assertEquals(listOf(10_000L, 20_000L), ran)
    }

    @Test
    fun keepsItsPlaceInTheQueueBehindEarlierCommands() {
        val worker = Worker()
        val ran = mutableListOf<String>()
        val seeks = LatestWins<Long>(worker::post) { ran += "seek $it" }
        worker.post { ran += "open" }
        seeks.offer(1L)
        worker.post { ran += "pause" }
        seeks.offer(2L)
        worker.drain()
        assertEquals(listOf("open", "seek 2", "pause"), ran)
    }

    @Test
    fun clearDropsWhatIsPendingButNotWhatComesAfter() {
        val worker = Worker()
        val ran = mutableListOf<Long>()
        val seeks = slot(worker, ran)
        seeks.offer(10_000L)
        seeks.clear()
        seeks.offer(20_000L)
        worker.drain()
        assertEquals(listOf(20_000L), ran)
    }

    @Test
    fun aRejectedPostDoesNotWedgeLaterOffers() {
        var accepting = false
        val worker = Worker()
        val ran = mutableListOf<Long>()
        val seeks = LatestWins<Long>({ task -> accepting && worker.post(task) }) { ran += it }
        seeks.offer(10_000L)
        accepting = true
        seeks.offer(20_000L)
        worker.drain()
        assertEquals(listOf(20_000L), ran)
    }
}
