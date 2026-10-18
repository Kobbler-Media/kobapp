package dev.kobbler.kobplayer

import kotlin.test.Test
import kotlin.test.assertEquals

class PositionPublishTest {

    @Test
    fun publishesEveryStepOrJumpAndHoldsTheRest() {
        listOf(
            Triple(1_000L, 1_041L, 1_000L),
            Triple(1_000L, 1_199L, 1_000L),
            Triple(1_000L, 1_200L, 1_200L),
            Triple(1_000L, 1_250L, 1_250L),
            Triple(1_000L, 60_000L, 60_000L),
            Triple(60_000L, 1_000L, 1_000L),
            Triple(1_000L, 900L, 900L),
            Triple(1_000L, 1_000L, 1_000L),
        ).forEach { (published, reported, expected) ->
            assertEquals(expected, publishedPosition(published, reported), "$published <- $reported")
        }
    }
}
