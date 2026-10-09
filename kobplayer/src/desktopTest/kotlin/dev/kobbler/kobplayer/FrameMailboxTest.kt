package dev.kobbler.kobplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class FrameMailboxTest {

    @Test
    fun rendererGetsNewestFrameAndSkipsStaleOnes() {
        val box = FrameMailbox(VlcPicturePool + 2)
        val a = box.lock().also(box::publish)
        val b = box.lock().also(box::publish)
        assertNotEquals(a, b)
        assertEquals(b, box.acquire())
    }

    @Test
    fun rendererKeepsShowingFrontWhenNothingNewArrived() {
        val box = FrameMailbox(VlcPicturePool + 2)
        val a = box.lock().also(box::publish).also(box::unlock)
        assertEquals(a, box.acquire())
        assertEquals(a, box.acquire())
    }

    @Test
    fun vlcNeverWritesIntoAFrameTheRendererMayStillRead() {
        val box = FrameMailbox(VlcPicturePool + 2)
        val front = box.lock().also(box::publish).also(box::unlock)
        box.acquire()
        val pending = box.lock().also(box::publish).also(box::unlock)
        repeat(VlcPicturePool) {
            val slot = box.lock()
            assertNotEquals(front, slot)
            assertNotEquals(pending, slot)
        }
    }

    @Test
    fun clearedMailboxShowsNothingUntilTheNextFrame() {
        val box = FrameMailbox(VlcPicturePool + 2)
        box.lock().also(box::publish).also(box::unlock)
        box.acquire()
        box.clear()
        assertNull(box.acquire())
        val next = box.lock().also(box::publish)
        assertEquals(next, box.acquire())
    }
}
