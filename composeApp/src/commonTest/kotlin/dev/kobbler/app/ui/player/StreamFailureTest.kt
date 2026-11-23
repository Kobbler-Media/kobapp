package dev.kobbler.app.ui.player

import dev.kobbler.app.data.ApiException
import kotlin.test.Test
import kotlin.test.assertEquals

class StreamFailureTest {
    private fun refused(reason: String) = ApiException(502, "Bad Gateway", "server text", reason)

    @Test
    fun usenetFailuresNameTheUsenetProvidersNotADebridService() {
        val cases = listOf(
            "not_cached" to "That stream wouldn't play. Parts of the release are gone from your usenet providers.",
            "unavailable" to "That stream wouldn't play. None of your usenet providers could serve it right now.",
            "timeout" to "That stream wouldn't play. Your usenet providers took too long to answer.",
            "not_video" to "That stream wouldn't play. The release isn't a playable video.",
        )
        for ((reason, expected) in cases) {
            assertEquals(expected, streamFailureSummary(refused(reason), tried = 1, source = "usenet"), reason)
        }
    }

    @Test
    fun debridFailuresKeepTheirCopy() {
        assertEquals(
            "That stream wouldn't play. The release isn't cached by your debrid service yet.",
            streamFailureSummary(refused("not_cached"), tried = 1, source = "torrent"),
        )
        assertEquals(
            "That stream wouldn't play. Your debrid service couldn't be reached.",
            streamFailureSummary(refused("unavailable"), tried = 1, source = null),
        )
    }
}
