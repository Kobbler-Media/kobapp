package dev.kobbler.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.kobbler.app.theme.KobappTheme

@Composable
fun WatchedMark(modifier: Modifier = Modifier) {
    val colors = KobappTheme.colors
    Box(
        modifier = modifier.size(18.dp).clip(CircleShape).background(colors.ok),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.Visibility, "Watched", tint = colors.bg, modifier = Modifier.size(12.dp))
    }
}
