package dev.kobbler.app.ui.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class KeepSessionWarmTest {

    @Test
    fun `a backgrounded app stops touching the session and checks it again on return`() = runTest {
        val foreground = MutableStateFlow(true)
        var touches = 0
        var lost = false
        val job = launch {
            keepSessionWarm(interval = 90.seconds, foreground = foreground, sessionGone = { touches++; touches > 2 }) { lost = true }
        }

        advanceTimeBy(91.seconds)
        runCurrent()
        assertEquals(1, touches)

        foreground.value = false
        advanceTimeBy(30.minutes)
        runCurrent()
        assertEquals(1, touches, "a backgrounded app kept touching the server")

        foreground.value = true
        runCurrent()
        assertEquals(2, touches, "returning to the app did not check the session straight away")

        advanceTimeBy(91.seconds)
        runCurrent()
        assertEquals(true, lost)
        job.cancel()
    }
}
