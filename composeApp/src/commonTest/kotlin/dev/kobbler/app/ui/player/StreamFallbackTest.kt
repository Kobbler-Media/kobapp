package dev.kobbler.app.ui.player

import dev.kobbler.app.data.LibraryEventFrame
import dev.kobbler.app.data.StreamOption
import dev.kobbler.app.data.StreamQuality
import dev.kobbler.app.data.StreamSearchResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamFallbackTest {

    private fun option(id: String, key: String?, fileId: Int?, cached: Boolean = false) =
        StreamOption(
            id = id, releaseTitle = id, size = 0, indexer = "", releaseKey = key,
            quality = StreamQuality(null, null, null, null), qualityJson = null,
            totalScore = 0.0, debridProvider = "realdebrid", debridProviderId = "p1",
            cached = cached, sourceType = "torrent", fileId = fileId, publishDate = null,
        )

    private fun warmReady(key: String, fileId: Int?, providerId: String = "p1") = LibraryEventFrame(
        "stream_warm_ready",
        Json.parseToJsonElement(
            """{"release_key":"$key",${fileId?.let { "\"file_id\":$it," }.orEmpty()}"debrid_provider_id":"$providerId","debrid_provider":"realdebrid"}""",
        ).jsonObject,
    )

    @Test
    fun warmReadyMarksOnlyTheFileThatWarmed() {
        // A season pack appears once per episode file under one key, so matching the
        // key alone would light up every sibling entry.
        val result = StreamSearchResult(
            options = listOf(
                option("a", key = "h1", fileId = 1),
                option("b", key = "h1", fileId = 2),
                option("c", key = "h2", fileId = 1),
            ),
            errors = emptyList(),
        )
        val out = applyWarmReady(result, warmReady("h1", fileId = 2))
        assertEquals(listOf(false, true, false), out!!.options.map { it.cached })
    }

    @Test
    fun warmReadyMarksAnImportedNzbButNoUnimportedOne() {
        val result = StreamSearchResult(
            options = listOf(
                option("nzb", key = "nzb-ab12", fileId = null),
                option("raw1", key = null, fileId = null),
                option("raw2", key = null, fileId = null),
            ),
            errors = emptyList(),
        )
        val out = applyWarmReady(result, warmReady("nzb-ab12", fileId = null))
        assertEquals(listOf(true, false, false), out!!.options.map { it.cached })
    }

    @Test
    fun warmReadyIgnoresAnotherProvider() {
        val result = StreamSearchResult(listOf(option("a", "h1", 1)), emptyList())
        val out = applyWarmReady(result, warmReady("h1", fileId = 1, providerId = "other"))
        assertEquals(listOf(false), out!!.options.map { it.cached })
    }

    @Test
    fun warmReadyIsIdentityWhenNothingMatches() {
        // Same instance back, so a collector does not churn state on every event.
        val result = StreamSearchResult(listOf(option("a", "h1", 1, cached = true)), emptyList())
        assertSame(result, applyWarmReady(result, warmReady("h1", 1)))
        assertSame(result, applyWarmReady(result, LibraryEventFrame("stream_warm_ready", buildJsonObject { put("info_hash", "h1") })))
        assertNull(applyWarmReady(null, warmReady("h1", 1)))
    }

    @Test
    fun theCapStaysWithinKobloadsWarmDepth() {
        // WARM_TOP_N in kobload's warm_cache.rs is 8: options past it were never
        // probed, so falling onto them trades one cold wait for another.
        assertTrue(MAX_AUTO_FALLBACK <= 8, "MAX_AUTO_FALLBACK=$MAX_AUTO_FALLBACK exceeds WARM_TOP_N")
    }
}
