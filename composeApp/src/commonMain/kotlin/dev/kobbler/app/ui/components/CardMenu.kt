package dev.kobbler.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import dev.kobbler.app.ui.detail.OverflowItem
import dev.kobbler.app.ui.detail.OverflowMenu

@Immutable
data class CardAction(val label: String, val onClick: () -> Unit, val icon: ImageVector? = null)

/** What a held card offers: a long press on touch, OK held down on a remote. */
@Composable
internal fun CardMenu(actions: List<CardAction>, expanded: Boolean, anchor: FocusRequester, accent: Color, onClose: () -> Unit) {
    if (actions.isEmpty()) return
    OverflowMenu(expanded = expanded, anchor = anchor, onClose = onClose) { close ->
        actions.forEach { action ->
            OverflowItem(
                key = action.label,
                icon = action.icon,
                label = action.label,
                accent = accent,
                onClick = {
                    close()
                    action.onClick()
                },
            )
        }
    }
}
