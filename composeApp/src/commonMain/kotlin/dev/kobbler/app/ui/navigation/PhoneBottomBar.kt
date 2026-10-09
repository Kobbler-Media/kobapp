package dev.kobbler.app.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.theme.Section
import dev.kobbler.app.theme.accentFor
import dev.kobbler.app.ui.safeBottomPadding

val KobPhoneBottomBarHeight: Dp = 62.dp

@Composable
fun KobPhoneBottomBar(
    items: List<SidebarItem>,
    active: Section,
    modifier: Modifier = Modifier,
    onSelect: (Section) -> Unit = {},
) {
    val colors = KobappTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.bg.copy(alpha = 0.92f))
            .border(width = 1.dp, color = colors.lineSoft, shape = RoundedCornerShape(0.dp))
            .safeBottomPadding()
            .height(KobPhoneBottomBarHeight)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEach { item ->
            val isActive = item.section == active
            val accent = colors.accentFor(item.section)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.weight(1f).fillMaxHeight()
                    .clickable { onSelect(item.section) },
            ) {
                Icon(item.icon, item.label, tint = if (isActive) accent else colors.textLo, modifier = Modifier.size(22.dp))
                Text(
                    item.label.substringBefore(' '),
                    color = if (isActive) accent else colors.textLo,
                    style = KobappTheme.type.caption,
                )
                if (isActive) {
                    Box(Modifier.size(width = 16.dp, height = 2.dp).clip(KobappTheme.shapes.pill).background(accent))
                } else {
                    Spacer(Modifier.height(2.dp))
                }
            }
        }
    }
}
