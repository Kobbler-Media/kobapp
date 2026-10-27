package dev.kobbler.app.ui.dashboard

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.kobbler.app.ui.components.KobChip
import dev.kobbler.app.ui.components.KobChipStyle
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup

internal const val SearchJumpChipsTag = "search-jump-chips"

@Composable
internal fun SearchJumpChips(jumps: List<SearchJump>, onJump: (SearchJump) -> Unit, modifier: Modifier = Modifier) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.testTag(SearchJumpChipsTag).focusKeyGroup("searchjump").horizontalScroll(rememberScrollState()),
    ) {
        jumps.forEach { jump ->
            KobChip(
                text = jump.label,
                style = KobChipStyle.Ghost,
                leading = { Icon(Icons.Outlined.ArrowDownward, contentDescription = null, modifier = Modifier.size(14.dp)) },
                onClick = { onJump(jump) },
                modifier = Modifier.focusKey("jump.${jump.label}", entryEligible = false),
            )
        }
    }
}
