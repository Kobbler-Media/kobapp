package dev.kobbler.app.ui.detail

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import dev.kobbler.app.UiTimeoutMs

@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.rowHas(label: String): Boolean =
    onAllNodes(hasContentDescription(label) or hasText(label)).fetchSemanticsNodes().isNotEmpty()

@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.openOverflowIfAny(): Boolean {
    if (onAllNodesWithContentDescription("More").fetchSemanticsNodes().isEmpty()) return false
    onNodeWithContentDescription("More").performClick()
    waitForIdle()
    return true
}

/** Wherever the row put it: on the row itself, or, if it did not fit, in the overflow. */
@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.performAction(label: String) {
    waitUntil(timeoutMillis = UiTimeoutMs) { rowHas(label) || onAllNodesWithContentDescription("More").fetchSemanticsNodes().isNotEmpty() }
    if (!rowHas(label)) openOverflowIfAny()
    waitUntil(timeoutMillis = UiTimeoutMs) { rowHas(label) }
    onAllNodes(hasContentDescription(label) or hasText(label)).onFirst().performClick()
    waitForIdle()
}

/** Each of [labels] is offered exactly once: on the row or in the overflow, never both, never neither. */
@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.assertOfferedOnce(vararg labels: String) {
    val texts = { label: String -> onAllNodesWithText(label).fetchSemanticsNodes().size }
    val onRow = labels.associateWith { onAllNodesWithContentDescription(it).fetchSemanticsNodes().size + texts(it) }
    val textsBefore = labels.associateWith(texts)
    openOverflowIfAny()
    for (label in labels) {
        val inMenu = texts(label) - textsBefore.getValue(label)
        kotlin.test.assertEquals(1, onRow.getValue(label) + inMenu, "$label: ${onRow.getValue(label)} on the row, $inMenu in the menu")
    }
}
