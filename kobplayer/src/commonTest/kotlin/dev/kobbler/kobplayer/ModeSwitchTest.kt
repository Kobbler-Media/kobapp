package dev.kobbler.kobplayer

import kotlin.test.Test
import kotlin.test.assertEquals

class ModeSwitchTest {

    @Test
    fun onlyLongFormAndLiveMayInterruptThePanelToMatchTheirRate() {
        listOf(
            Triple(0L, false, false),
            Triple(150_000L, false, false),
            Triple(599_999L, false, false),
            Triple(600_000L, false, true),
            Triple(7_680_000L, false, true),
            Triple(0L, true, true),
        ).forEach { (durationMs, live, expected) ->
            assertEquals(expected, worthAModeSwitch(durationMs, live), "$durationMs live=$live")
        }
    }

    private val pixel = listOf(DisplayMode(1, 1080, 2400, 60f), DisplayMode(2, 1080, 2400, 120f))
    private val tv = listOf(
        DisplayMode(1, 3840, 2160, 60f), DisplayMode(2, 3840, 2160, 59.94f), DisplayMode(3, 3840, 2160, 50f),
        DisplayMode(4, 1920, 1080, 24f), DisplayMode(5, 3840, 2160, 23.976f),
    )

    @Test
    fun theDisplayIsAskedForTheLowestRateThatRepeatsEveryFrameEvenly() {
        listOf(
            Triple(pixel, 24f, 2),
            Triple(pixel, 30f, null),
            Triple(pixel.reversed(), 30f, 1),
            Triple(tv, 25f, 3),
            Triple(tv, 24f, 5),
            Triple(tv, 23.976f, 5),
            Triple(tv, 59.94f, 2),
            Triple(tv.filter { it.id <= 3 }, 24f, null),
            Triple(pixel, 0f, null),
        ).forEach { (modes, fps, expected) ->
            assertEquals(expected, displayModeFor(modes, current = modes.first(), frameRate = fps), "${modes.map { it.refreshHz }} at $fps")
        }
    }
}
