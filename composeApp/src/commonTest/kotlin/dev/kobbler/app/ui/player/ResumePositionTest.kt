package dev.kobbler.app.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResumePositionTest {

    @Test
    fun aRealResumeIsCarriedIntoTheResolveRequest() {
        assertEquals(1200, resumeRequestSeconds(1200))
    }

    @Test
    fun aMisTapIsNotAResume() {
        assertNull(resumeRequestSeconds(0))
        assertNull(resumeRequestSeconds(5))
        assertNull(resumeRequestSeconds(null))
        assertEquals(6, resumeRequestSeconds(6))
    }

    @Test
    fun directPlayResumesOnTheClient() {
        // The server reports no start time for direct play — it hands over the whole
        // file and the client is the only thing that can move the playhead.
        assertEquals(1200, resumeSeekSeconds(saved = 1200, serverStartSeconds = null))
    }

    @Test
    fun aRebasedTranscodeIsAlreadyAtThePosition() {
        // An HLS session created with start_time serves a playlist whose first segment
        // IS the resume point, so its timeline starts at zero. Seeking again would jump
        // a second 20 minutes forward, off the end of what has been produced.
        assertEquals(0, resumeSeekSeconds(saved = 1200, serverStartSeconds = 1200))
    }

    @Test
    fun nothingWatchedMeansNoSeek() {
        assertEquals(0, resumeSeekSeconds(saved = null, serverStartSeconds = null))
        assertEquals(0, resumeSeekSeconds(saved = 3, serverStartSeconds = null))
    }

    @Test
    fun aSegmentAlignedRebaseLeavesTheRemainderToTheClient() {
        // The server snaps its rebase back to a segment boundary, so the playlist starts
        // a little before the position that was asked for.
        assertEquals(12, resumeSeekSeconds(saved = 1200, serverStartSeconds = 1188))
    }
}
