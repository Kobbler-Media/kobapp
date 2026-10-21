package dev.kobbler.app.ui.player

import dev.kobbler.app.data.StreamOption
import dev.kobbler.app.data.StreamQuality
import dev.kobbler.app.data.StreamSearchResult
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamFallbackTest {

    private fun option(id: String, hash: String?, fileId: Int?, cached: Boolean = false) =
        StreamOption(
            id = id, releaseTitle = id, size = 0, indexer = "", infoHash = hash,
            quality = StreamQuality(null, null, null, null), qualityJson = null,
            totalScore = 0.0, debridProvider = "realdebrid", debridProviderId = "p1",
            cached = cached, sourceType = "torrent", fileId = fileId, publishDate = null,
        )

    @Test
    fun warmReadyMarksOnlyTheFileThatWarmed() {
        // A season pack appears once per episode file under one hash, so matching the
        // hash alone would light up every sibling entry.
        val result = StreamSearchResult(
            options = listOf(
                option("a", hash = "h1", fileId = 1),
                option("b", hash = "h1", fileId = 2),
                option("c", hash = "h2", fileId = 1),
            ),
            errors = emptyList(),
        )
        val out = applyWarmReady(result, infoHash = "h1", fileId = 2, debridProviderId = "p1")!!
        assertEquals(listOf(false, true, false), out.options.map { it.cached })
    }

    @Test
    fun warmReadyIgnoresAnotherProvider() {
        val result = StreamSearchResult(listOf(option("a", "h1", 1)), emptyList())
        val out = applyWarmReady(result, infoHash = "h1", fileId = 1, debridProviderId = "other")!!
        assertEquals(listOf(false), out.options.map { it.cached })
    }

    @Test
    fun warmReadyIsIdentityWhenNothingMatches() {
        // Same instance back, so a collector does not churn state on every event.
        val result = StreamSearchResult(listOf(option("a", "h1", 1, cached = true)), emptyList())
        assertSame(result, applyWarmReady(result, "h1", 1, "p1"))
        assertNull(applyWarmReady(null, "h1", 1, "p1"))
    }

    @Test
    fun theCapStaysWithinKobloadsWarmDepth() {
        // WARM_TOP_N in kobload's warm_cache.rs is 8: options past it were never
        // probed, so falling onto them trades one cold wait for another.
        assertTrue(MAX_AUTO_FALLBACK <= 8, "MAX_AUTO_FALLBACK=$MAX_AUTO_FALLBACK exceeds WARM_TOP_N")
    }
}
