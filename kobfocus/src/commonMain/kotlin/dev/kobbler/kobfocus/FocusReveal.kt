package dev.kobbler.kobfocus

import androidx.compose.ui.Modifier
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.findNearestAncestor
import androidx.compose.ui.platform.InspectorInfo

/**
 * Lets a restore reach a target that has not been composed: a lazy list's row scrolled out
 * of view, or never scrolled into it on a page that was rebuilt.
 *
 * When a restore names a key under this element's [focusKeyGroup] that nothing has
 * registered, [reveal] is handed the key relative to that group — `"row.80"` — and should
 * bring the target into composition, returning whether it knows the key at all.
 */
fun Modifier.focusReveal(reveal: suspend (key: String) -> Boolean): Modifier =
    this.then(FocusRevealElement(reveal))

private data class FocusRevealElement(
    val reveal: suspend (String) -> Boolean,
) : ModifierNodeElement<FocusRevealNode>() {
    override fun create() = FocusRevealNode(reveal)

    override fun update(node: FocusRevealNode) {
        node.reveal = reveal
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "focusReveal"
    }
}

private class FocusRevealNode(
    var reveal: suspend (String) -> Boolean,
) : Modifier.Node(), CompositionLocalConsumerModifierNode {

    private var registered: Pair<FocusScope, String>? = null
    private val revealer: suspend (String) -> Boolean = { reveal(it) }

    override fun onAttach() {
        val prefix = (findNearestAncestor(FocusKeyNode::class) as? FocusKeyNode)?.fullKey.orEmpty()
        val scope = currentValueOf(LocalFocusScope)
        scope.registerReveal(prefix, revealer)
        registered = scope to prefix
    }

    override fun onDetach() {
        registered?.let { (scope, prefix) -> scope.unregisterReveal(prefix, revealer) }
        registered = null
    }
}
