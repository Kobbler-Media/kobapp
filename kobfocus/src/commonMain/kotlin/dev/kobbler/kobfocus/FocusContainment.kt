package dev.kobbler.kobfocus

import androidx.compose.foundation.focusGroup
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalFocusManager

/**
 * Removes this subtree from focus traversal while [inert] is true.
 *
 * Painting one screen over another does not take the one underneath out of the focus
 * graph — every card behind the overlay stays a live D-pad target. Any layer that is
 * composed but not the top one must be marked inert, or focus escapes into it.
 *
 * `canFocus = false` alone is not enough: it deactivates the group node itself but
 * leaves its descendants reachable, so traversal still walks straight in. Rejecting
 * entry outright is what actually contains the subtree.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.focusInert(inert: Boolean): Modifier =
    if (!inert) {
        this
    } else {
        this
            .focusProperties {
                canFocus = false
                onEnter = { cancelFocusChange() }
            }
            .then(FocusEvictElement)
            .focusGroup()
    }

private object FocusEvictElement : ModifierNodeElement<FocusEvictNode>() {
    override fun create() = FocusEvictNode()
    override fun update(node: FocusEvictNode) = Unit
    override fun InspectorInfo.inspectableProperties() {
        name = "focusInert"
    }
    override fun hashCode() = 0
    override fun equals(other: Any?) = other === this
}

/**
 * Evicts focus that was already inside when the layer went inert.
 *
 * Rejecting entry says nothing about focus that is *already* here — it simply stays,
 * on an element the viewer can no longer see. The overlay that covered this layer then
 * asks for entry focus, finds something already holding it, and places none of its own:
 * the panel comes up with a dead D-pad while a control behind it is lit.
 */
private class FocusEvictNode :
    Modifier.Node(),
    FocusEventModifierNode,
    CompositionLocalConsumerModifierNode {

    override fun onFocusEvent(focusState: FocusState) {
        if (!focusState.hasFocus) return
        currentValueOf(LocalFocusManager).clearFocus(force = true)
    }
}
