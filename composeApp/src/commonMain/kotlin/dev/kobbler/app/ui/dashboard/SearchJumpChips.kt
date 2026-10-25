package dev.kobbler.app.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kobbler.app.ui.components.KobChip
import dev.kobbler.app.ui.components.KobChipStyle
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup

@Composable
internal fun SearchJumpChips(jumps: List<SearchJump>, onJump: (SearchJump) -> Unit, modifier: Modifier = Modifier) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.focusKeyGroup("searchjump"),
    ) {
        jumps.forEach { jump ->
            KobChip(
                text = jump.group.label,
                style = KobChipStyle.Ghost,
                leading = { Icon(Icons.Outlined.ArrowDownward, contentDescription = null, modifier = Modifier.size(14.dp)) },
                onClick = { onJump(jump) },
                modifier = Modifier.focusKey("jump.${jump.group.name}", entryEligible = false),
            )
        }
    }
}
