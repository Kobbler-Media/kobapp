package dev.kobbler.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PipDismissalTest {

    @Test
    fun `a stop is a dismissal only when the viewer could have closed the window`() {
        data class Case(val name: String, val stop: StopSignals, val dismissed: Boolean)
        listOf(
            Case("close button, mode change first", StopSignals(pipJustExited = true, screenOn = true), true),
            Case("close button, still nominally in PiP", StopSignals(inPip = true, screenOn = true), true),
            Case("close button that finishes the activity", StopSignals(finishing = true, screenOn = true), true),
            Case("screen off while in PiP", StopSignals(inPip = true, screenOn = false), false),
            Case("screen off while fullscreen", StopSignals(screenOn = false), false),
            Case("home from fullscreen", StopSignals(screenOn = true), false),
            Case("finishing with the screen off", StopSignals(finishing = true, screenOn = false), true),
        ).forEach { assertEquals(it.name, it.dismissed, it.stop.dismissesPip()) }
    }
}
