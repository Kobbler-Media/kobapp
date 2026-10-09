package dev.kobbler.app.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class PlayerTimelineTest {

    @Test
    fun directPlayIsTheIdentity() {
        val t = PlayerTimeline.Direct
        for (ms in listOf(0L, 1L, 5_000L, 1_200_000L, 3_600_000L)) {
            assertEquals(ms, t.toAbsoluteMs(ms))
            assertEquals(ms, t.toPlayerMs(ms))
            assertEquals(ms, t.absoluteDurationMs(ms))
        }
    }

    @Test
    fun aSessionWithoutAServerStartIsDirect() {
        assertSame(PlayerTimeline.Direct, PlayerTimeline.of(null))
        assertSame(PlayerTimeline.Direct, PlayerTimeline.of(0))
        assertSame(PlayerTimeline.Direct, PlayerTimeline.of(-3))
    }

    @Test
    fun aRebasedTimelineShiftsBothWays() {
        val t = PlayerTimeline.of(1200)
        assertEquals(1_200_000L, t.offsetMs)
        assertEquals(1_200_000L, t.toAbsoluteMs(0))
        assertEquals(1_260_000L, t.toAbsoluteMs(60_000))
        assertEquals(60_000L, t.toPlayerMs(1_260_000))
        assertEquals(0L, t.toPlayerMs(1_200_000))
    }

    @Test
    fun aTargetBeforeTheRebaseClampsToTheStartOfWhatIsServed() {
        // The segments before the rebase point are not in the playlist at all.
        assertEquals(0L, PlayerTimeline.of(1200).toPlayerMs(10_000))
    }

    @Test
    fun theTitleIsLongerThanWhatThePlayerWasHanded() {
        val t = PlayerTimeline.of(1200)
        assertEquals(3_600_000L, t.absoluteDurationMs(2_400_000))
        // An unparsed source reports no duration; it must not become the offset.
        assertEquals(0L, t.absoluteDurationMs(0))
    }

    @Test
    fun roundTrippingAPositionIsLossless() {
        val t = PlayerTimeline.of(1200)
        for (playerMs in listOf(0L, 1L, 999L, 60_000L, 2_400_000L)) {
            assertEquals(playerMs, t.toPlayerMs(t.toAbsoluteMs(playerMs)))
        }
    }
}
