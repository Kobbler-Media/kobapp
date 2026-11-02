package dev.kobbler.app.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.tvAutoGlow
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup
import dev.kobbler.kobplayer.Player
import dev.kobbler.kobplayer.PlayerState

@Composable
internal fun ListenAlongBar(player: Player?, theme: ReaderTheme, onStop: () -> Unit) {
    if (player == null) return
    val spState by player.state.collectAsState()
    val spMs by player.currentMs.collectAsState()
    val spDur by player.durationMs.collectAsState()
    val spSpeed by player.playbackSpeed.collectAsState()

    Row(
        modifier = Modifier
            .focusKeyGroup("listenalong")
            .fillMaxWidth()
            .background(theme.backgroundColor)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Headphones, null, tint = theme.linkColor, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = { player.togglePlayPause() },
            modifier = Modifier
                .focusKey("playpause")
                .size(32.dp)
                .tvAutoGlow(cornerRadiusDp = 999f, scale = 1.08f),
        ) {
            Icon(
                if (spState == PlayerState.PLAYING) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                "Play/Pause", tint = theme.textColor, modifier = Modifier.size(20.dp),
            )
        }
        Slider(
            value = if (spDur > 0) spMs.toFloat() / spDur else 0f,
            onValueChange = { player.seekTo((it * spDur).toLong()) },
            modifier = Modifier.weight(1f).height(24.dp),
            colors = SliderDefaults.colors(thumbColor = theme.linkColor, activeTrackColor = theme.linkColor),
        )
        Box(
            Modifier
                .focusKey("speed")
                .width(48.dp)
                .tvAutoGlow(cornerRadiusDp = 6f, scale = 1.03f)
                .clickable {
                    val speeds = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
                    val nextIdx = (speeds.indexOf(spSpeed) + 1) % speeds.size
                    player.setPlaybackSpeed(speeds[nextIdx])
                }
                .padding(PaddingValues(horizontal = 4.dp, vertical = 6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("${spSpeed}x", style = KobappTheme.type.caption.copy(color = theme.textColor))
        }
        IconButton(
            onClick = onStop,
            modifier = Modifier
                .focusKey("stop")
                .size(32.dp)
                .tvAutoGlow(cornerRadiusDp = 999f, scale = 1.08f),
        ) {
            Icon(Icons.Outlined.Close, "Stop", tint = theme.textColor, modifier = Modifier.size(18.dp))
        }
    }
}
