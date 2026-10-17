package dev.kobbler.app.ui.dashboard

import kotlin.test.Test
import kotlin.test.assertEquals

class BrowseGridTest {
    @Test
    fun slackIsWhatNoWholeColumnFits() {
        val cases = listOf(
            // viewport, start, end, cell, gap -> slack
            listOf(520, 10, 10, 100, 0) to 0,
            listOf(530, 10, 10, 100, 0) to 10,
            listOf(560, 20, 20, 100, 12) to 84,
            listOf(50, 10, 10, 100, 12) to 0,
        )
        for ((args, slack) in cases) {
            val (viewport, start, end, cell, gap) = args
            assertEquals(slack, fixedGridSlackPx(viewport, start, end, cell, gap), "$args")
        }
    }
}
