package dev.kobbler.app.ui.reader

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** A reader left open on one page wakes the radio every 30 s; only a move is worth sending. */
class ProgressSaverTest {

    @Test
    fun anUnchangedPositionIsNotSentAgain() = runTest {
        val sent = mutableListOf<Double>()
        val saver = ProgressSaver<Double> { sent += it }
        saver.save(12.5)
        saver.save(12.5)
        saver.save(13.0)
        saver.save(13.0)
        assertEquals(listOf(12.5, 13.0), sent)
    }

    @Test
    fun aFailedSaveIsRetriedNextTime() = runTest {
        var fail = true
        val sent = mutableListOf<Double>()
        val saver = ProgressSaver<Double> {
            if (fail) error("offline")
            sent += it
        }
        saver.save(40.0)
        fail = false
        saver.save(40.0)
        assertEquals(listOf(40.0), sent)
    }
}
