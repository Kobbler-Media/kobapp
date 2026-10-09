package dev.kobbler.app.ui.components

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class DataCacheTest {

    /** Unconfined runs each coroutine eagerly on this thread, so ordering here is deterministic. */
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @BeforeTest
    fun reset() {
        DataCache.clear()
    }

    @AfterTest
    fun cleanup() {
        DataCache.clear()
    }

    @Test
    fun versionOfReturnsZeroForUnknownKey() {
        assertEquals(0, DataCache.versionOf("never-seen"))
    }

    @Test
    fun invalidateBumpsOnlyNamedKeys() {
        DataCache.invalidate("home")
        assertEquals(1, DataCache.versionOf("home"))
        assertEquals(0, DataCache.versionOf("dashboard"))
        assertEquals(0, DataCache.versionOf("movies"))
    }

    @Test
    fun invalidateAcceptsMultipleKeys() {
        DataCache.invalidate("home", "dashboard", "movies")
        assertEquals(1, DataCache.versionOf("home"))
        assertEquals(1, DataCache.versionOf("dashboard"))
        assertEquals(1, DataCache.versionOf("movies"))
        assertEquals(0, DataCache.versionOf("books"))
    }

    @Test
    fun repeatedInvalidatesAccumulate() {
        repeat(3) { DataCache.invalidate("home") }
        assertEquals(3, DataCache.versionOf("home"))
    }

    @Test
    fun invalidatePrefixBumpsKeysRegisteredByPut() {
        DataCache.put("show-abc", 1)
        DataCache.put("show-def", 2)
        DataCache.put("movie-123", 3)
        DataCache.put("home", 4)

        DataCache.invalidatePrefix("show-")

        assertEquals(1, DataCache.versionOf("show-abc"))
        assertEquals(1, DataCache.versionOf("show-def"))
        assertEquals(0, DataCache.versionOf("movie-123"))
        assertEquals(0, DataCache.versionOf("home"))
    }

    @Test
    fun invalidatePrefixKeepsStaleValueForRevalidation() {
        DataCache.put("show-abc", "cached")
        DataCache.invalidatePrefix("show-")
        assertEquals("cached", DataCache.get<String>("show-abc"))
        assertFalse(DataCache.isFresh("show-abc"))
    }

    @Test
    fun invalidatePrefixOnEmptyCacheIsNoOp() {
        DataCache.invalidatePrefix("show-")
        assertEquals(0, DataCache.versionOf("show-abc"))
    }

    @Test
    fun clearResetsVersionsAndCache() {
        DataCache.put("home", "value")
        DataCache.invalidate("home", "dashboard")
        DataCache.clear()
        assertEquals(0, DataCache.versionOf("home"))
        assertEquals(0, DataCache.versionOf("dashboard"))
        assertNull(DataCache.get<String>("home"))
    }

    @Test
    fun putAndGetRoundTrip() {
        DataCache.put("home", listOf(1, 2, 3))
        assertEquals(listOf(1, 2, 3), DataCache.get<List<Int>>("home"))
    }

    // -- TTL --

    @Test
    fun freshlyPutEntryIsFresh() {
        DataCache.put("home", "v")
        assertTrue(DataCache.isFresh("home"))
    }

    @Test
    fun unknownKeyIsNeverFresh() {
        assertFalse(DataCache.isFresh("never-seen"))
    }

    @Test
    fun invalidateExpiresTtlButKeepsValue() {
        DataCache.put("home", "v")
        DataCache.invalidate("home")
        assertFalse(DataCache.isFresh("home"))
        assertEquals("v", DataCache.get<String>("home"))
    }

    @Test
    fun ttlIsPerKeyFamily() {
        val cases = listOf(
            "home" to 45.seconds,
            "dashboard" to 45.seconds,
            "movie-123" to 45.seconds,
            "letters" to 10.minutes,
            "authors" to 5.minutes,
            "series" to 5.minutes,
            "series-42" to 5.minutes,
        )
        cases.forEach { (key, expected) -> assertEquals(expected, DataCache.ttlFor(key), key) }
    }

    // -- Eviction --

    @Test
    fun cacheEvictsOldestBeyondCapacity() {
        repeat(31) { DataCache.put("k$it", it) }
        assertNull(DataCache.get<Int>("k0"))
        assertEquals(1, DataCache.get<Int>("k1"))
        assertEquals(30, DataCache.get<Int>("k30"))
    }

    @Test
    fun rewritingAKeyMovesItToTheBackOfTheEvictionQueue() {
        repeat(30) { DataCache.put("k$it", it) }
        DataCache.put("k0", 100)
        DataCache.put("k30", 30)
        assertEquals(100, DataCache.get<Int>("k0"))
        assertNull(DataCache.get<Int>("k1"))
    }

    // -- In-flight deduplication --

    @Test
    fun concurrentLoadsOfOneKeyShareASingleRequest() {
        var calls = 0
        val gate = CompletableDeferred<Unit>()
        var first: String? = null
        var second: String? = null

        scope.launch { first = DataCache.load("home") { calls++; gate.await(); "v" } }
        scope.launch { second = DataCache.load("home") { calls++; gate.await(); "v" } }
        assertEquals(1, calls)

        gate.complete(Unit)
        assertEquals("v", first)
        assertEquals("v", second)
        assertEquals(1, calls)
        assertEquals("v", DataCache.get<String>("home"))
    }

    @Test
    fun distinctKeysDoNotShareARequest() {
        var calls = 0
        // Assigned, not discarded: as the last expression of `launch` the call's expected
        // type is Unit, so T infers as Unit and the loader's value never reaches the cache.
        var a: String? = null
        var b: String? = null
        scope.launch { a = DataCache.load("home") { calls++; "a" } }
        scope.launch { b = DataCache.load("dashboard") { calls++; "b" } }
        assertEquals(2, calls)
        assertEquals("a", a)
        assertEquals("b", b)
        assertEquals("a", DataCache.get<String>("home"))
        assertEquals("b", DataCache.get<String>("dashboard"))
    }

    @Test
    fun aWaiterTakesOverWhenTheRunningLoadIsCancelled() {
        var calls = 0
        val gate = CompletableDeferred<Unit>()
        var result: String? = null

        var abandoned: String? = null
        val running = scope.launch { abandoned = DataCache.load("home") { calls++; gate.await(); "abandoned" } }
        scope.launch { result = DataCache.load("home") { calls++; "taken-over" } }
        running.cancel()

        assertEquals("taken-over", result)
        assertEquals(2, calls)
        assertEquals("taken-over", DataCache.get<String>("home"))
    }

    @Test
    fun aFailedLoadIsNotCachedAndDoesNotPoisonTheKey() {
        var attempts = 0
        var failure: Throwable? = null
        scope.launch {
            runCatching { DataCache.load<String>("home") { attempts++; error("boom") } }
                .onFailure { failure = it }
        }
        assertEquals("boom", failure?.message)
        assertNull(DataCache.get<String>("home"))

        var result: String? = null
        scope.launch { result = DataCache.load("home") { attempts++; "ok" } }
        assertEquals("ok", result)
        assertEquals(2, attempts)
    }
}
