package dev.kobbler.app.ui.player

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerFailureTest {

    @Test
    fun aRealFailureIsReported() {
        val gps = GlobalPlayerState()
        gps.isPreparing = true

        gps.failWith(IllegalStateException("no playable source"), listOf("Movie: Obsession"))

        val error = gps.loadingError
        assertTrue(error != null && error.contains("no playable source"), "got: $error")
        assertTrue(error.contains("Movie: Obsession"))
        assertEquals(false, gps.isPreparing)
    }

    /**
     * The context block is the whole point of reporting: without the stream URL a
     * report cannot say whether the server handed over a direct `.ts` or an HLS
     * playlist, which is the first thing a playback bug turns on.
     */
    @Test
    fun theDiagnosticContextCarriesWhatTheServerCannotSee() {
        val gps = GlobalPlayerState()
        gps.title = "ABC NEWS"
        gps.mediaType = "livetv"
        gps.mediaId = "chan-1"
        gps.liveSessionId = "sess-9"
        gps.isLive = true

        val context = gps.diagnosticContext()
        assertTrue(context.any { it.startsWith("App: ") }, "missing build line: $context")
        assertTrue(context.any { it.contains("ABC NEWS") }, "missing title: $context")
        assertTrue(context.any { it.contains("livetv") }, "missing media: $context")
        assertTrue(context.any { it.contains("sess-9") }, "missing session: $context")
    }

    /**
     * A libVLC failure and a session failure can both fire for one attempt. The
     * session one is more specific, so it wins rather than being overwritten by
     * the generic "could not open".
     */
    @Test
    fun aPlayerErrorDoesNotOverwriteAMoreSpecificSessionError() {
        val gps = GlobalPlayerState()
        gps.failWith(IllegalStateException("Server returned no stream URL"))
        val first = gps.loadingError

        gps.reportPlayerFailure(RuntimeException("VLC playback error"))

        assertEquals(first, gps.loadingError, "the second, vaguer failure replaced the first")
    }

    @Test
    fun aPlayerErrorOnItsOwnStillRaisesTheDialog() {
        val gps = GlobalPlayerState()
        gps.mediaType = "livetv"

        gps.reportPlayerFailure(RuntimeException("VLC playback error"))

        val error = gps.loadingError
        assertTrue(error != null && error.contains("VLC playback error"), "got: $error")
        assertTrue(error.contains("App: "), "report lacks the build/device line: $error")
    }

    /**
     * A new failure must be reportable even though the last one already went, or
     * the second channel you try reports nothing.
     */
    @Test
    fun eachNewFailureIsReportedAfresh() {
        val gps = GlobalPlayerState()

        gps.failWith(IllegalStateException("first"))
        assertEquals(ReportStatus.Idle, gps.reportStatus)

        gps.failWith(IllegalStateException("second"))
        assertEquals(ReportStatus.Idle, gps.reportStatus)
    }

    @Test
    fun cancellationIsNotAPlaybackFailure() {
        // Leaving the screen cancels the in-flight resolve. That is us stopping, not
        // playback failing, and it must never raise a dialog.
        val gps = GlobalPlayerState()
        gps.isPreparing = true

        gps.failWith(CancellationException("The coroutine scope left the composition"))

        assertNull(gps.loadingError)
        assertEquals(false, gps.isPreparing)
    }
}
