package dev.kobbler.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.tvAutoGlow
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup
import dev.kobbler.kobplayer.ui.MediaSeekBar
import dev.kobbler.kobplayer.ui.formatTime

@Composable
fun CastMiniController(
    deviceName: String,
    title: String,
    subtitle: String? = null,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    onPlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KobappTheme.colors
    val type = KobappTheme.type
    val castTint = colors.accCal
    val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs) else 0f
    // What the finger is on, which leads the receiver: a scrub is a round trip to the
    // other end of the house, and the next broadcast from it is up to five seconds away.
    var scrubFraction by remember(positionMs) { mutableStateOf<Float?>(null) }

    Column(
        modifier = modifier
            .focusKeyGroup("cast")
            .fillMaxWidth()
            .height(104.dp)
            .background(colors.elev1),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.lineSoft),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(width = 44.dp, height = 56.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(colors.elev2),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Cast,
                    contentDescription = null,
                    tint = castTint,
                    modifier = Modifier.size(22.dp),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(castTint),
                    )
                    Text(
                        text = "Casting to $deviceName",
                        style = type.caption,
                        color = castTint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = title.ifBlank { "Now casting" },
                    style = type.body,
                    color = colors.textHi,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = type.caption,
                        color = colors.textLo,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            CastTransportButton(
                key = "playpause",
                icon = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                description = if (isPlaying) "Pause" else "Play",
                tint = colors.textHi,
                iconSize = 26.dp,
                onClick = onPlayPause,
            )

            CastTransportButton(
                key = "disconnect",
                icon = Icons.Outlined.Stop,
                description = "Disconnect",
                tint = colors.textLo,
                iconSize = 22.dp,
                onClick = onDisconnect,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val shown = scrubFraction?.let { (it * durationMs).toLong() } ?: positionMs
            Text(formatTime(shown), style = type.caption, color = colors.textLo)
            // The player's own bar, so a scrub reads and behaves the same whether the
            // picture is here or across the room — including a step per D-pad press.
            MediaSeekBar(
                progress = { scrubFraction ?: progress },
                onSeek = { fraction ->
                    scrubFraction = fraction
                    onSeekTo((fraction * durationMs).toLong())
                },
                onSeeking = { scrubFraction = it },
                modifier = Modifier.weight(1f),
                activeColor = castTint,
                inactiveColor = colors.textHi.copy(alpha = 0.18f),
                focusDecoration = Modifier.focusKey("seek"),
                dpadStepFraction = if (durationMs > 0) (10_000f / durationMs) else 0f,
            )
            Text(formatTime(durationMs), style = type.caption, color = colors.textLo)
        }
    }
}

@Composable
private fun CastTransportButton(
    key: String,
    icon: ImageVector,
    description: String,
    tint: Color,
    iconSize: Dp,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .focusKey(key)
            .size(40.dp)
            .tvAutoGlow(cornerRadiusDp = 999f, scale = 1.08f)
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = tint, modifier = Modifier.size(iconSize))
    }
}
