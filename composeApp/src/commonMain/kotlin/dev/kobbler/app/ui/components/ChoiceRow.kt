package dev.kobbler.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.kobfocus.focusKey

/** One option in a pick-one dialog: D-pad focusable, ticked when it is the current choice. */
@Composable
fun ChoiceRow(
    label: String,
    detail: String?,
    selected: Boolean,
    accent: Color,
    focusKeyName: String,
    onClick: () -> Unit,
    preferredEntry: Boolean = false,
) {
    val colors = KobappTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .focusKey(focusKeyName, preferredEntry = preferredEntry)
            .tvAutoGlow(cornerRadiusDp = 10f, scale = TvFocusScale.None)
            .clip(RoundedCornerShape(10.dp))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                color = if (selected) accent else colors.textHi,
                style = KobappTheme.type.body.copy(fontWeight = FontWeight.SemiBold),
            )
            if (detail != null) {
                Text(detail, color = colors.textLo, style = KobappTheme.type.caption)
            }
        }
        if (selected) {
            Icon(Icons.Outlined.Check, "Selected", tint = accent, modifier = Modifier.size(18.dp))
        }
    }
}
