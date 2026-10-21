package dev.kobbler.app.ui.player

import dev.kobbler.app.data.ApiException
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.StreamOption
import dev.kobbler.app.data.StreamQuality
import dev.kobbler.app.data.StreamResolveResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
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
    private fun refused(reason: String): StreamResolveResult = throw ApiException(502, "Bad Gateway", reason, reason)

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

    private suspend fun Script.walk(from: List<StreamOption> = options, onAttemptFailed: () -> Unit = {}) =
        walkStreamOptions(candidates = from, resolve = { resolve(it) }, onAttemptFailed = onAttemptFailed)

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
    fun theWalkIsBoundedByItsBudget() = runTest {
        val s = Script(emptyMap())
        val out = s.walk()
        assertIs<WalkResult.Exhausted>(out)
        assertEquals(MAX_AUTO_FALLBACK + 1, s.started.size)
        assertEquals(MAX_AUTO_FALLBACK + 1, out.attempts)
    }

    @Test
    fun refusalsAreInstantSkipsThatSpendNothing() = runTest {
        val s = Script(mapOf(
            "a" to { refused("blocked") },
            "b" to { refused("not_video") },
            "c" to { refused("blocked") },
            "e" to { ready("e") },
        ))
        var notes = 0
        val out = walkStreamOptions(options, resolve = { s.resolve(it) }, attempts = 2, onAttemptFailed = { notes++ })
        assertIs<WalkResult.Ready>(out)
        assertEquals("e", out.option.id)
        assertEquals(2, out.attempts, "a refusal was charged as an attempt")
        assertEquals(1, notes, "announced a retry for an instant refusal")
    }

    /** The incident: three refusals and a caching answer used up the walk before anything that would play. */
    @Test
    fun cachingAndRefusalsDoNotHideAPlayableOptionFurtherDown() = runTest {
        val s = Script(mapOf(
            "a" to { caching(options[0]) },
            "b" to { refused("blocked") },
            "c" to { refused("blocked") },
            "d" to { refused("blocked") },
            "e" to { caching(options[4]) },
            "f" to { refused("blocked") },
            "g" to { ready("g") },
        ))
        val out = s.walk(fallbackCandidates(options, from = null))
        assertIs<WalkResult.Ready>(out)
        assertEquals("g", out.option.id)
    }

    @Test
    fun uncachedOptionsGoLastAndOnlyOneIsAskedToCache() = runTest {
        val list = listOf(
            option("a").copy(availability = "uncached"),
            option("b").copy(availability = "ready"),
            option("c").copy(availability = "unknown"),
            option("d").copy(availability = "uncached"),
        )
        assertEquals(listOf("b", "c", "a", "d"), fallbackCandidates(list, from = null).map { it.id })

        val s = Script(mapOf("a" to { caching(list[0]) }, "d" to { caching(list[3]) }))
        val out = s.walk(fallbackCandidates(list, from = null))
        assertIs<WalkResult.Caching>(out)
        assertEquals("a", out.option.id)
        assertEquals(listOf("b", "c", "a"), s.started, "queued a second download on the debrid service")
    }

    @Test
    fun aCachingAnswerAlreadyInHandSparesTheUncached() = runTest {
        val list = listOf(option("a"), option("b").copy(availability = "uncached"))
        val s = Script(mapOf("a" to { caching(list[0]) }, "b" to { caching(list[1]) }))
        val out = s.walk(fallbackCandidates(list, from = null))
        assertIs<WalkResult.Caching>(out)
        assertEquals(listOf("a"), s.started)
    }

    @Test
    fun aRateLimitStopsTheWalk() = runTest {
        val s = Script(mapOf("b" to { refused("rate_limited") }, "c" to { ready("c") }))
        val out = s.walk()
        assertIs<WalkResult.Exhausted>(out)
        assertEquals("rate_limited", (out.lastError as ApiException).reason)
        assertEquals(listOf("a", "b"), s.started)
    }

    @Test
    fun theWalkIsBoundedByResolves() = runTest {
        val many = (1..30).map { option("x$it") }
        val s = Script(many.associate { o -> o.id to suspend { refused("blocked") } })
        s.walk(many)
        assertEquals(MAX_WALK_RESOLVES, s.started.size)
    }

    @Test
    fun theWalkIsBoundedByTime() = runTest {
        val s = Script(options.associate { o -> o.id to suspend { delay(12_000); refused("blocked") } })
        walkStreamOptions(options, resolve = { s.resolve(it) }, timeSource = testTimeSource)
        assertEquals(3, s.started.size, "kept resolving past the time cap")
    }

    @Test
    fun knownFailuresGoAfterTheRest() {
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
