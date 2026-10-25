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
import androidx.compose.ui.node.TraversableNode.Companion.TraverseDescendantsAction
import androidx.compose.ui.node.traverseDescendants
import kotlinx.coroutines.launch

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
fun Modifier.focusInert(inert: Boolean): Modifier {
    // First in the chain so the node survives the toggle: it has to still be here, holding
    // what it evicted, when the layer comes back.
    val evicting = this.then(FocusInertElement(inert))
    return if (!inert) {
        evicting
    } else {
        evicting
            .focusProperties {
                canFocus = false
                onEnter = { cancelFocusChange() }
            }
            .focusGroup()
    }
}

private data class FocusInertElement(val inert: Boolean) : ModifierNodeElement<FocusInertNode>() {
    override fun create() = FocusInertNode(inert)
    override fun update(node: FocusInertNode) = node.update(inert)
    override fun InspectorInfo.inspectableProperties() {
        name = "focusInert"
        properties["inert"] = inert
    }
}

/**
 * Evicts focus that was already inside when the layer went inert, and hands it back when
 * the layer is uncovered.
 *
 * Rejecting entry says nothing about focus that is *already* here — it simply stays,
 * on an element the viewer can no longer see. The overlay that covered this layer then
 * asks for entry focus, finds something already holding it, and places none of its own:
 * the panel comes up with a dead D-pad while a control behind it is lit.
 *
 * Nothing else gives it back: the overlay's own focused element is simply removed, which
 * leaves focus nowhere, so the control that opened a picker was lost on every close.
 */
private class FocusInertNode(private var inert: Boolean) :
    Modifier.Node(),
    FocusEventModifierNode,
    CompositionLocalConsumerModifierNode {

    private var hasFocus = false
    private var evicting = false
    private var evicted: Pair<FocusScope, String>? = null

    fun update(inert: Boolean) {
        if (this.inert == inert) return
        this.inert = inert
        if (inert) {
            if (!hasFocus) return
            recordFocused()
            // The clear waits for the end of the apply: this runs mid-diff, before the group
            // that seals the layer is attached. On Android a cleared focus is asked straight
            // back by the root view, whose enter search walked into the unsealed layer,
            // re-entered here, and recursed until the stack overflowed. Whether to clear is
            // decided now, though — once sealed, the group reports itself rather than the
            // focused element inside it.
            sideEffect { if (this.inert) clear() }
        } else {
            handBack()
        }
    }

    override fun onFocusEvent(focusState: FocusState) {
        hasFocus = focusState.hasFocus
        if (inert && hasFocus) evict()
    }

    private fun evict() {
        recordFocused()
        clear()
    }

    // The first eviction per cover is the one the viewer made; later ones are entry
    // attempts that strayed in here and must not replace it.
    private fun recordFocused() {
        if (evicted != null) return
        focusedKeyNode()?.let { evicted = it.scope to it.fullKey }
    }

    private fun clear() {
        if (evicting) return
        evicting = true
        try {
            currentValueOf(LocalFocusManager).clearFocus(force = true)
        } finally {
            evicting = false
        }
    }

    private fun handBack() {
        val (scope, key) = evicted ?: return
        evicted = null
        coroutineScope.launch {
            if (!scope.restore(key)) scope.focusFirst()
        }
    }

    private fun focusedKeyNode(): FocusKeyNode? {
        var found: FocusKeyNode? = null
        traverseDescendants(FocusKeyNode::class) { node ->
            if (node is FocusKeyNode && node.isFocused) {
                found = node
                TraverseDescendantsAction.CancelTraversal
            } else {
                TraverseDescendantsAction.ContinueTraversal
            }
        }
        return found
    }
}
