package dev.kobbler.app.ui.components

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.kobplayer.ui.HoverLabel

@Composable
internal fun LabelledIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = KobappTheme.colors.textHi,
) {
    HoverLabel(label) {
        IconButton(onClick = onClick, enabled = enabled, modifier = modifier.tvAutoGlow(cornerRadiusDp = 999f, scale = 1.08f)) {
            Icon(icon, label, tint = tint)
        }
    }
}
