package dev.kobbler.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.kobbler.app.theme.KobappTheme

// Shown for local files and debrid streams alike: where the copy comes from is not
// what the viewer is asking when they look at a resume position.
@Composable
fun WatchProgressBar(
    positionSeconds: Int?,
    totalSeconds: Int?,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    if (positionSeconds == null || positionSeconds <= 0) return
    val colors = KobappTheme.colors
    val fraction = if (totalSeconds != null && totalSeconds > 0) {
        (positionSeconds.toFloat() / totalSeconds).coerceIn(0f, 1f)
    } else null
    val remaining = if (totalSeconds != null && totalSeconds > positionSeconds + 30) {
        totalSeconds - positionSeconds
    } else null

    Column(modifier.widthIn(max = 420.dp)) {
        if (fraction != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.textLo.copy(alpha = 0.25f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(accent),
                )
            }
            Spacer(Modifier.height(6.dp))
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                buildString {
                    append(formatWatchTime(positionSeconds))
                    if (totalSeconds != null && totalSeconds > 0) {
                        append(" / ")
                        append(formatWatchTime(totalSeconds))
                    }
                },
                style = KobappTheme.type.caption,
                color = colors.textLo,
            )
            if (remaining != null) {
                Spacer(Modifier.width(12.dp))
                Text(
                    "${formatWatchTime(remaining)} left",
                    style = KobappTheme.type.caption,
                    color = colors.textLo,
                )
            }
        }
    }
}

fun formatWatchTime(totalSeconds: Int): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    else "$m:${s.toString().padStart(2, '0')}"
}

fun remainingLabel(seconds: Int): String? {
    if (seconds < 60) return null
    val minutes = seconds / 60
    return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m left" else "${minutes}m left"
}
