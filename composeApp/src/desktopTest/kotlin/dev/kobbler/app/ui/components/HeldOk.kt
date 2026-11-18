package dev.kobbler.app.ui.components

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions

/**
 * OK held on a remote on [node] until its long-press menu opens, then let go. Android keeps
 * repeating the key-down for as long as the button is held, and once the menu is up its
 * focused entry receives those repeats and the release.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.holdOk(node: SemanticsNodeInteraction) {
    node.performSemanticsAction(SemanticsActions.RequestFocus)
    waitForIdle()
    node.performKeyInput { keyDown(Key.DirectionCenter) }
    mainClock.advanceTimeBy(1_000)
    waitForIdle()
    val menu = onAllNodes(isRoot()).onFirst()
    repeat(3) { menu.performKeyPress(repeatedDown(Key.DirectionCenter)) }
    menu.performKeyInput { keyUp(Key.DirectionCenter) }
    waitForIdle()
}

@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.pressOkInMenu() {
    onAllNodes(isRoot()).onFirst().performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
    waitForIdle()
}

@OptIn(InternalComposeUiApi::class)
private fun repeatedDown(key: Key) = KeyEvent(key, KeyEventType.KeyDown)
