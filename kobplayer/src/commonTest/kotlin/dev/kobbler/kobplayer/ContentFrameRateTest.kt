package dev.kobbler.kobplayer

import kotlin.test.Test
import kotlin.test.assertEquals

class ContentFrameRateTest {

    @Test
    fun readsTheTrackRateOrDeclinesToVote() {
        listOf(
            // Voted as the whole rate: the Pixel's selector files 23.976 under 60Hz, where film judders.
            Triple(24000, 1001, 24f),
            Triple(24, 1, 24f),
            Triple(25, 1, 25f),
            Triple(60000, 1001, 60f),
            Triple(30000, 1001, 30f),
            Triple(15, 1, 15f),
            Triple(0, 0, 0f),
            Triple(24, 0, 0f),
            Triple(-24, 1, 0f),
            // Matroska without a default duration surfaces its 1ms timebase as the rate.
            Triple(1000, 1, 0f),
        ).forEach { (num, den, expected) ->
            assertEquals(expected, contentFrameRate(num, den), 0.001f, "$num/$den")
        }
    }

    @Test
    fun snapsAMeasuredRateToTheBroadcastRateItCameFrom() {
        listOf(
            Triple(96L, 4_000L, 24f),
            Triple(100L, 4_000L, 25f),
            Triple(120L, 4_000L, 30f),
            Triple(200L, 4_000L, 50f),
            Triple(240L, 4_000L, 60f),
            // A stall inside the window: no standard rate is near, so no vote.
            Triple(70L, 4_000L, 0f),
            Triple(0L, 4_000L, 0f),
            Triple(96L, 0L, 0f),
        ).forEach { (pictures, elapsedMs, expected) ->
            assertEquals(expected, measuredFrameRate(pictures, elapsedMs), 0.001f, "$pictures in $elapsedMs ms")
        }
    }
}
