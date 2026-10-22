package dev.kobbler.app.ui.player

import dev.kobbler.app.data.ApiException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A channel that will not tune reads as a sentence about that channel, not a stack trace. */
class LiveFailureSummaryTest {

    private fun failed(mediaType: String, cause: Throwable) = GlobalPlayerState().apply {
        this.mediaType = mediaType
        title = "7HD Gippsland"
        failWith(cause, listOf("Channel: 7HD Gippsland"))
    }

    @Test
    fun `every live failure carries a summary naming the channel`() {
        val causes = listOf(
            RuntimeException("VLC playback error"),
            IllegalStateException("Stream keeps failing — try a different source"),
            ApiException(503, "Service Unavailable"),
        )
        for (cause in causes) {
            val gps = failed("livetv", cause)
            val summary = assertNotNull(gps.failureHelp, "$cause").summary
            assertTrue("7HD Gippsland" in summary, summary)
            assertTrue("Exception" !in summary, summary)
            assertNotNull(gps.loadingError, "the diagnostic is still kept for details")
        }
    }

    @Test
    fun `the server's reason is what the viewer reads`() {
        val gps = failed("livetv", ApiException(503, "Service Unavailable", serverMessage = "The tuner couldn't lock onto 7HD Gippsland (weak signal)."))
        assertEquals(
            "Couldn't tune 7HD Gippsland. The tuner couldn't lock onto 7HD Gippsland (weak signal).",
            gps.failureHelp?.summary,
        )
    }

    @Test
    fun `other media keep their own help`() {
        assertNull(failed("movie", RuntimeException("VLC playback error")).failureHelp)
    }

    /** VLC only says the stream died; the server already said why, through the buffer poll. */
    @Test
    fun `a tune error the server reported names the reason when the player only saw a dead stream`() {
        val gps = GlobalPlayerState().apply {
            mediaType = "livetv"
            title = "7HD Gippsland"
            liveTuneError = "7HD Gippsland has no signal right now (the tuner couldn't lock onto it)"
            failWith(RuntimeException("VLC playback error"))
        }
        assertEquals(
            "Couldn't tune 7HD Gippsland. 7HD Gippsland has no signal right now (the tuner couldn't lock onto it)",
            gps.failureHelp?.summary,
        )
    }
}
