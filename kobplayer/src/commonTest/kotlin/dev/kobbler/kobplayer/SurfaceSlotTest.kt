package dev.kobbler.kobplayer

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SurfaceSlotTest {

    private class Surface(val name: String)

    @Test
    fun aRenewedSlotHandsBackTheSurfaceThatReplacesTheOneGivenUp() = runTest {
        val slot = SurfaceSlot<Surface>()
        val old = Surface("old")
        slot.created(old)

        val renewed = async { slot.renew(timeoutMs = 1_000) }
        yield()
        assertEquals(1, slot.epoch.value, "nothing was asked to build a new surface")
        assertNull(slot.current, "the abandoned surface is still offered")
        assertFalse(slot.ready.value)

        val fresh = Surface("fresh")
        slot.created(fresh)
        assertSame(fresh, renewed.await())
        assertTrue(slot.ready.value)
    }

    /** The old view goes away after the new one is up as often as before it. */
    @Test
    fun theAbandonedSurfaceGoingAwayLateLeavesItsReplacementAlone() = runTest {
        val slot = SurfaceSlot<Surface>()
        val old = Surface("old")
        slot.created(old)
        val renewed = async { slot.renew(timeoutMs = 1_000) }
        yield()
        val fresh = Surface("fresh")
        slot.created(fresh)
        renewed.await()

        assertFalse(slot.destroyed(old))
        assertSame(fresh, slot.current)
        assertTrue(slot.ready.value)
        assertTrue(slot.destroyed(fresh))
        assertNull(slot.current)
    }

    @Test
    fun aSlotWithNoSurfaceHasNothingToRenew() = runTest {
        val slot = SurfaceSlot<Surface>()
        assertNull(slot.renew(timeoutMs = 1_000))
        assertEquals(0, slot.epoch.value)
    }

    @Test
    fun aReplacementThatNeverArrivesIsNotWaitedOnForever() = runTest {
        val slot = SurfaceSlot<Surface>()
        slot.created(Surface("old"))
        assertNull(slot.renew(timeoutMs = 500))
        assertNull(slot.current)
    }
}
