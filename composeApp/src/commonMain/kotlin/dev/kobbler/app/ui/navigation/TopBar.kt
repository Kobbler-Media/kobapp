package dev.kobbler.app.ui.navigation

import dev.kobbler.app.ui.components.Edge
import dev.kobbler.app.ui.components.edgeLine
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import dev.kobbler.app.platform.CastButton
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.TvFocusScale
import dev.kobbler.app.ui.components.tvAutoGlow

// The bar floats over Home's hero, where the D-pad reaches it with Up; without a ring a
// focused control here reads as focus having vanished.
private const val IconRingRadius = 20f

@Composable
fun KobTopBar(
    sectionLabel: String? = null,
    castingTo: String? = null,
    notificationCount: Int = 0,
    dense: Boolean = false,
    showCast: Boolean = true,
    transparent: Boolean = false,
    isFullscreen: Boolean = false,
    modifier: Modifier = Modifier,
    onSearchClick: () -> Unit = {},
    onNotificationsClick: () -> Unit = {},
    onToggleFullscreen: (() -> Unit)? = null,
) {
    val colors = KobappTheme.colors
    val accent = KobappTheme.accent
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (transparent) {
                    // Only enough scrim to keep the controls legible over whatever runs under the bar.
                    Modifier.background(Brush.verticalGradient(listOf(colors.bg.copy(alpha = 0.6f), Color.Transparent)))
                } else {
                    Modifier
                        .background(colors.bg.copy(alpha = 0.92f))
                        .edgeLine(Edge.Bottom, colors.lineSoft)
                },
            )
            .padding(horizontal = if (dense) 20.dp else 28.dp, vertical = if (dense) 12.dp else 16.dp),
    ) {
        if (sectionLabel != null) {
            Text(sectionLabel, style = KobappTheme.type.h1, color = colors.textHi)
        }
        Spacer(Modifier.weight(1f))
        // Search affordance
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .tvAutoGlow(cornerRadiusDp = 10f, scale = TvFocusScale.None)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.elev2)
                .border(1.dp, colors.lineSoft, RoundedCornerShape(10.dp))
                .clickable(onClick = onSearchClick)
                .padding(horizontal = 12.dp, vertical = 7.dp),
        ) {
            Icon(Icons.Outlined.Search, "Search", tint = colors.textMd, modifier = Modifier.size(16.dp))
            Text("Search library & Discover", color = colors.textLo, style = KobappTheme.type.bodySm)
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.clip(RoundedCornerShape(4.dp))
                    .border(1.dp, colors.line, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text("⌘K", color = colors.textXlo, style = KobappTheme.type.caption)
            }
        }
        // Casting indicator
        if (castingTo != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .clip(KobappTheme.shapes.pill)
                    .background(lerp(colors.elev1, colors.accTv, 0.18f))
                    .border(1.dp, lerp(Color.Transparent, colors.accTv, 0.28f), KobappTheme.shapes.pill)
                    .padding(start = 6.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
            ) {
                Box(Modifier.size(22.dp).clip(RoundedCornerShape(5.dp)).background(colors.accTv))
                Text(castingTo, color = colors.textHi, style = KobappTheme.type.caption)
                Text("● casting", color = colors.accTv, style = KobappTheme.type.caption)
            }
        }
        if (showCast) {
            CastButton(Modifier.tvAutoGlow(IconRingRadius, TvFocusScale.None), colors.textMd)
        }
        Box {
            IconButton(onClick = onNotificationsClick, modifier = Modifier.tvAutoGlow(IconRingRadius, TvFocusScale.None)) {
                Icon(Icons.Outlined.Notifications, "Notifications", tint = colors.textMd)
            }
            if (notificationCount > 0) {
                Box(
                    Modifier
                        .padding(top = 8.dp, end = 8.dp)
                        .align(Alignment.TopEnd)
                        .size(7.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(accent),
                )
            }
        }
        if (onToggleFullscreen != null) {
            IconButton(onClick = onToggleFullscreen, modifier = Modifier.tvAutoGlow(IconRingRadius, TvFocusScale.None)) {
                Icon(
                    if (isFullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                    contentDescription = if (isFullscreen) "Exit fullscreen" else "Fullscreen",
                    tint = colors.textMd,
                )
            }
        }
    }
}
