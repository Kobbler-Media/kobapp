package dev.kobbler.app.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaybackTraceTest {

    @Test
    fun `a finished trace names every stage in order`() {
        PlaybackTrace.begin("play movie Arrival")
        PlaybackTrace.mark("session")
        PlaybackTrace.mark("parse")
        PlaybackTrace.mark("decoder")
        PlaybackTrace.end()

        val line = PlaybackTrace.last
        assertTrue(line != null && line.startsWith("play movie Arrival — "), "got $line")
        assertTrue(
            line.indexOf("session") < line.indexOf("parse") &&
                line.indexOf("parse") < line.indexOf("decoder") &&
                line.indexOf("decoder") < line.indexOf("frame"),
            "stages are out of order: $line",
        )
    }

    /**
     * PLAYING is reached again on every resume from a pause, and a resume is not a
     * start-up cost. Only the one that closes an open trace may publish.
     */
    @Test
    fun `reaching play outside a trace publishes nothing`() {
        PlaybackTrace.begin("first")
        PlaybackTrace.end()
        val afterFirst = PlaybackTrace.last

        PlaybackTrace.end()
        PlaybackTrace.mark("stray")
        PlaybackTrace.end()

        assertEquals(afterFirst, PlaybackTrace.last, "a bare end() overwrote the last trace")
    }

}
