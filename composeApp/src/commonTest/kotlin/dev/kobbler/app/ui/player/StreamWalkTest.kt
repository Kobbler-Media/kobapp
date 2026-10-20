package dev.kobbler.app.ui.player

import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.StreamOption
import dev.kobbler.app.data.StreamQuality
import dev.kobbler.app.data.StreamResolveResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StreamWalkTest {

    private fun option(id: String) = StreamOption(
        id = id, releaseTitle = id, size = 0, indexer = "", infoHash = "h-$id",
        quality = StreamQuality(null, null, null, null), qualityJson = null,
        totalScore = 0.0, debridProvider = "realdebrid", debridProviderId = "p1",
        cached = false, sourceType = "torrent", fileId = null, publishDate = null,
    )

    private val options = listOf("a", "b", "c", "d", "e", "f", "g", "h").map(::option)

    private fun ready(id: String) = StreamResolveResult.Ready(KobblerApi.StreamSession(url = "u-$id", sessionId = "s-$id"))
    private fun caching(o: StreamOption) = StreamResolveResult.Caching("p1", "t-${o.id}", o)

    private class Script(val plan: Map<String, suspend () -> StreamResolveResult>) {
        val started = mutableListOf<String>()
        var inFlight = 0
        var peak = 0
        suspend fun resolve(o: StreamOption): StreamResolveResult {
            started += o.id
            inFlight++
            peak = maxOf(peak, inFlight)
            try {
                return plan[o.id]?.invoke() ?: error("${o.id} is dead")
            } finally {
                inFlight--
            }
        }
    }

    private suspend fun Script.walk(from: List<StreamOption> = options) =
        walkStreamOptions(candidates = from, resolve = { resolve(it) })

    @Test
    fun theFirstThatPlaysIsTakenWithoutTouchingTheRest() = runTest {
        val s = Script(mapOf("a" to { ready("a") }))
        val out = s.walk()
        assertIs<WalkResult.Ready>(out)
        assertEquals("a", out.option.id)
        assertEquals(listOf("a"), s.started)
    }

    @Test
    fun deadOptionsAreWalkedOneAtATimeInOrder() = runTest {
        val s = Script(mapOf("c" to { ready("c") }))
        val out = s.walk()
        assertIs<WalkResult.Ready>(out)
        assertEquals("c", out.option.id)
        assertEquals(listOf("a", "b", "c"), s.started)
        assertEquals(listOf("a", "b", "c"), out.tried.map { it.id })
        assertEquals(1, s.peak, "two resolves were in flight at once")
    }

    @Test
    fun cachingOptionsAreSkippedAndTheBestRankedOneIsKept() = runTest {
        val s = Script(mapOf(
            "a" to { caching(options[0]) },
            "b" to { caching(options[1]) },
        ))
        val out = s.walk(options.take(4))
        assertIs<WalkResult.Caching>(out)
        assertEquals("a", out.option.id)
        assertEquals(listOf("a", "b", "c", "d"), s.started)
    }

    @Test
    fun everythingDeadIsExhaustedWithTheLastReason() = runTest {
        val s = Script(emptyMap())
        val out = s.walk(options.take(3))
        assertIs<WalkResult.Exhausted>(out)
        assertEquals("c is dead", out.lastError?.message)
        assertEquals(3, out.tried.size)
    }

    @Test
    fun theWalkIsBoundedByItsBudget() {
        val all = options + options.map { it.copy(id = it.id + "2") }
        val picked = fallbackCandidates(all, from = all[1], skip = setOf("c"))
        assertEquals(listOf("b", "d", "e", "f", "g", "h"), picked.map { it.id })
        assertEquals(MAX_AUTO_FALLBACK + 1, picked.size)
    }

    @Test
    fun knownFailuresGoLast() {
        val bad = options[0].copy(playback = "failed")
        val list = listOf(bad) + options.drop(1).take(2)
        val picked = fallbackCandidates(list, from = bad)
        assertEquals(listOf("a", "b", "c"), picked.map { it.id }, "an explicit pick still goes first")
        val auto = fallbackCandidates(list, from = null)
        assertEquals(listOf("b", "c", "a"), auto.map { it.id })
    }

    @Test
    fun aCancelledWalkStopsWhereItIs() = runTest {
        val gate = CompletableDeferred<Unit>()
        val s = Script(mapOf("a" to { gate.await(); ready("a") }))
        val job = launch { s.walk() }
        advanceUntilIdle()
        job.cancel()
        advanceUntilIdle()
        assertEquals(0, s.inFlight)
        assertTrue(s.started == listOf("a"), "kept walking after the cancel: ${s.started}")
    }
}
