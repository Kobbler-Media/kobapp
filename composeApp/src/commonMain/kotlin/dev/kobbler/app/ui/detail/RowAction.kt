package dev.kobbler.app.ui.detail

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.kobfocus.focusKey

internal class RowAction(
    val key: String,
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val detail: String? = null,
    val progress: Float? = null,
    val remaining: String? = null,
    val tint: Color? = null,
    val enabled: Boolean = true,
    val selected: Boolean = false,
    val control: (@Composable (lead: Boolean) -> Unit)? = null,
    val menu: (@Composable ColumnScope.(close: () -> Unit) -> Unit)? = null,
)

@Composable
internal fun RowActionControl(action: RowAction, lead: Boolean, accent: Color, fill: Modifier = Modifier) {
    action.control?.let { return it(lead) }
    val focus = Modifier.focusKey(action.key, preferredEntry = lead)
    if (lead) {
        PrimaryAction(
            icon = action.icon,
            label = action.label,
            onClick = action.onClick,
            modifier = focus.then(fill),
            enabled = action.enabled,
            detail = action.detail,
            progress = action.progress,
            remaining = action.remaining,
        )
    } else {
        IconAction(
            icon = action.icon,
            label = action.label,
            onClick = action.onClick,
            accent = accent,
            modifier = focus,
            tint = action.tint ?: if (action.selected) accent else KobappTheme.colors.textHi,
            enabled = action.enabled,
        )
    }
}

@Composable
internal fun ColumnScope.RowActionMenuEntry(action: RowAction, accent: Color, close: () -> Unit) {
    action.menu?.let { return it(close) }
    OverflowItem(
        key = action.key,
        icon = action.icon,
        label = listOfNotNull(action.label, action.detail).joinToString(" "),
        accent = accent,
        enabled = action.enabled,
        selected = action.selected,
        onClick = {
            close()
            action.onClick()
        },
    )
}
