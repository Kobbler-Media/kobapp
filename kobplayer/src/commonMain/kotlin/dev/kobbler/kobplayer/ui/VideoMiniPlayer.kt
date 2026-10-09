package dev.kobbler.kobplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.kobbler.kobplayer.Player

/**
 * YouTube-style mini player bar. Shows at the bottom of the screen when video
 * is playing in the background (user navigated away while playing).
 *
 * Generic — no app-specific coupling. Pass colors to match your theme.
 */
@Composable
fun VideoMiniPlayer(
    player: Player,
    title: String,
    subtitle: String? = null,
    posterUrl: String? = null,
    visible: Boolean,
    onExpand: () -> Unit,
    onClose: () -> Unit,
    surfaceColor: Color = Color(0xFF181818),
    surfaceVariantColor: Color = Color(0xFF222222),
    onSurfaceColor: Color = Color(0xFFD4D4D4),
    onSurfaceMutedColor: Color = Color(0xFF6B6B6B),
    modifier: Modifier = Modifier,
) {
    if (!visible) return

    val isPlaying by player.isPlaying.collectAsState()
    val currentMs by player.currentMs.collectAsState()
    val durationMs by player.durationMs.collectAsState()
    val progress = if (durationMs > 0) currentMs.toFloat() / durationMs.toFloat() else 0f

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
            .background(surfaceColor)
            .clickable(onClick = onExpand),
    ) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = Color.White,
            trackColor = surfaceVariantColor,
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (posterUrl != null) {
                AsyncImage(
                    model = posterUrl, contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)).background(surfaceVariantColor),
                )
            }

            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, color = onSurfaceColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.labelSmall, color = onSurfaceMutedColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            IconButton(onClick = { player.togglePlayPause() }, modifier = Modifier.size(40.dp)) {
                Icon(
                    if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    "Play/Pause", tint = onSurfaceColor, modifier = Modifier.size(28.dp),
                )
            }

            IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.Close, "Close", tint = onSurfaceMutedColor, modifier = Modifier.size(20.dp))
            }
        }
    }
}
