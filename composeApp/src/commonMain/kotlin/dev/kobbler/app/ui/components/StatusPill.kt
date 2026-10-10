package dev.kobbler.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.kobbler.app.theme.KobappTheme

enum class PillStatus { InLibrary, Wanted, NotInLibrary }

@Composable
fun StatusPill(
    status: PillStatus,
    modifier: Modifier = Modifier,
) {
    val colors = KobappTheme.colors
    when (status) {
        // "Not in library" is the default state of everything in Discover — saying so on
        // every tile is noise, so it renders nothing at all.
        PillStatus.NotInLibrary -> Unit
        PillStatus.InLibrary -> StatusMark(Icons.Outlined.Check, "In library", colors.ok, modifier)
        PillStatus.Wanted -> StatusMark(Icons.Outlined.Schedule, "Wanted", colors.warn, modifier)
    }
}

// A word on a poster is unreadable at TV distance and covers the art; an icon reads at a
// glance (the clock: asked for, still coming).
@Composable
private fun StatusMark(icon: ImageVector, description: String, tint: Color, modifier: Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .border(1.dp, lerp(Color.Transparent, tint, 0.5f), CircleShape),
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(16.dp))
    }
}
