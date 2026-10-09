package dev.kobbler.kobfocus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.TraversableNode
import androidx.compose.ui.node.TraversableNode.Companion.TraverseDescendantsAction
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.findNearestAncestor
import androidx.compose.ui.node.traverseDescendants
import androidx.compose.ui.platform.InspectorInfo

/**
 * Marks this element as a restorable focus target within the enclosing [FocusScopeHost].
 *
 * [key] only has to be unique among siblings — the full identity is derived by walking
 * up the modifier tree, so a rail item ends up as `"home.continue.4"` without any call
 * site having to know its ancestors.
 *
 * Put this on the focusable leaf. Containers use [focusKeyGroup].
 *
 * Set [entryEligible] to false for page chrome — an alphabet bar, a filter strip — that
 * should never be what a screen hands entry focus to.
 */
@Composable
fun Modifier.focusKey(
    key: String,
    focusRequester: FocusRequester = remember { FocusRequester() },
    preferredEntry: Boolean = false,
    entryEligible: Boolean = true,
): Modifier = this
    .focusRequester(focusRequester)
    .then(FocusKeyElement(key, focusRequester, preferredEntry, entryEligible))

/**
 * Contributes [key] to the identity of everything beneath it without being a focus
 * target itself — for rails, sections and other grouping containers.
 *
 * A container is not usually focusable, so its focus events report the state of the
 * nearest target below it; recording those would let the container claim its own
 * child's identity. Groups therefore never record.
 */
@Composable
fun Modifier.focusKeyGroup(key: String): Modifier =
    this.then(FocusKeyElement(key, focusRequester = null))

private data class FocusKeyElement(
    val key: String,
    val focusRequester: FocusRequester?,
    val preferredEntry: Boolean = false,
    val entryEligible: Boolean = true,
) : ModifierNodeElement<FocusKeyNode>() {
    override fun create() = FocusKeyNode(key, focusRequester, preferredEntry, entryEligible)

    override fun update(node: FocusKeyNode) =
        node.update(key, focusRequester, preferredEntry, entryEligible)

    override fun InspectorInfo.inspectableProperties() {
        name = "focusKey"
        properties["key"] = key
    }
}

private class FocusKeyNode(
    var key: String,
    var focusRequester: FocusRequester?,
    var preferredEntry: Boolean = false,
    var entryEligible: Boolean = true,
) : Modifier.Node(),
    TraversableNode,
    FocusEventModifierNode,
    CompositionLocalConsumerModifierNode {

    override val traverseKey: Any = FocusKeyNode::class

    private var registeredKey: String? = null
    private var preferenceDirty = false

    private val fullKey: String
        get() {
            val parent = findNearestAncestor(traverseKey) as? FocusKeyNode
            return parent?.let { "${it.fullKey}.$key" } ?: key
        }

    private val scope get() = currentValueOf(LocalFocusScope)

    override fun onAttach() = updateRegistration()

    override fun onDetach() {
        val requester = focusRequester
        if (requester != null) {
            registeredKey?.let {
                scope.onBlurred(it)
                scope.unregister(it, requester)
            }
        }
        registeredKey = null
    }

    fun update(
        key: String,
        focusRequester: FocusRequester?,
        preferredEntry: Boolean,
        entryEligible: Boolean,
    ) {
        val keyChanged = this.key != key
        val requesterChanged = this.focusRequester !== focusRequester
        val preferenceChanged =
            this.preferredEntry != preferredEntry || this.entryEligible != entryEligible
        if (!keyChanged && !requesterChanged && !preferenceChanged) return

        this.key = key
        this.focusRequester = focusRequester
        this.preferredEntry = preferredEntry
        this.entryEligible = entryEligible
        preferenceDirty = preferenceChanged
        updateRegistration()
        // Descendants embed our key in theirs, so they all have to re-register.
        if (keyChanged) notifyDescendants()
    }

    private fun notifyDescendants() {
        traverseDescendants(traverseKey) { node ->
            if (node is FocusKeyNode) {
                node.updateRegistration()
                node.notifyDescendants()
                TraverseDescendantsAction.SkipSubtreeAndContinueTraversal
            } else {
                TraverseDescendantsAction.ContinueTraversal
            }
        }
    }

    private fun updateRegistration() {
        val requester = focusRequester ?: return
        val newKey = fullKey
        if (newKey == registeredKey && !preferenceDirty) return
        preferenceDirty = false
        registeredKey?.let { scope.unregister(it, requester) }
        registeredKey = newKey
        scope.register(newKey, requester, preferredEntry, entryEligible)
    }

    override fun onFocusEvent(focusState: FocusState) {
        if (focusRequester == null) return
        if (focusState.isFocused) scope.onFocused(fullKey) else scope.onBlurred(fullKey)
    }
}
