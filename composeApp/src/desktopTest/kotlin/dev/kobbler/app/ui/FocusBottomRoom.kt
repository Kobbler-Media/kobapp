package dev.kobbler.app.ui

import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.test.assertTrue

/** A focus ring and scale-up overflow the card by about this much. */
val FocusRingRoom: Dp = 16.dp

/** Presses DOWN until focus stops moving, then checks the last stop's ring has room below it. */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.assertRoomBelowLastDownStop(focus: () -> FocusManager, name: String = "") {
    var last: Int? = null
    run walk@{
        repeat(80) {
            runOnIdle { focus().moveFocus(FocusDirection.Down) }
            mainClock.advanceTimeBy(600)
            waitForIdle()
            val now = onAllNodes(isFocused()).fetchSemanticsNodes().firstOrNull()?.id
            if (now != null && now == last) return@walk
            last = now
        }
    }
    val focused = onNode(isFocused()).getUnclippedBoundsInRoot()
    val viewport = onRoot().getUnclippedBoundsInRoot()
    val room = viewport.bottom - focused.bottom
    assertTrue(room >= FocusRingRoom, "$name: last focus stop ends $room above the screen's bottom")
}
