package dev.kobbler.app.ui.player.audiobook

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay30
import androidx.compose.material.icons.outlined.VolumeDown
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.tvAutoGlow
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup
import dev.kobbler.kobplayer.PlayerState

@Composable
fun TransportRow(
    state: PlayerState,
    speed: Float,
    volume: Float,
    onPlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onVolumeChange: (Float) -> Unit,
    playSize: Dp = 88.dp,
    sideSize: Dp = 64.dp,
    modifier: Modifier = Modifier,
) {
    val colors = KobappTheme.colors
    val accent = colors.accAudio
    Row(
        modifier = modifier.focusKeyGroup("transport").fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpeedSelector(speed = speed, onSpeedChange = onSpeedChange)

        Spacer(Modifier.width(8.dp))

        IconButton(
            onClick = onSkipBack,
            modifier = Modifier
                .focusKey("skipback")
                .size(sideSize)
                .tvAutoGlow(cornerRadiusDp = 999f, scale = 1.08f),
        ) {
            Icon(
                Icons.Outlined.Replay30,
                "Back 30s",
                tint = colors.textHi,
                modifier = Modifier.size(sideSize * 0.55f),
            )
        }

        Spacer(Modifier.width(8.dp))

        Box(
            modifier = Modifier
                .focusKey("playpause")
                .size(playSize)
                .tvAutoGlow(cornerRadiusDp = 999f, scale = 1.06f)
                .clip(CircleShape)
                .background(accent)
                .clickable(onClick = onPlayPause),
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                PlayerState.LOADING, PlayerState.BUFFERING -> CircularProgressIndicator(
                    modifier = Modifier.size(playSize * 0.4f),
                    color = colors.bg,
                    strokeWidth = 2.5.dp,
                )
                else -> Icon(
                    if (state == PlayerState.PLAYING) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = if (state == PlayerState.PLAYING) "Pause" else "Play",
                    tint = colors.bg,
                    modifier = Modifier.size(playSize * 0.5f),
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        IconButton(
            onClick = onSkipForward,
            modifier = Modifier
                .focusKey("skipforward")
                .size(sideSize)
                .tvAutoGlow(cornerRadiusDp = 999f, scale = 1.08f),
        ) {
            Icon(
                Icons.Outlined.Forward10,
                "Forward 10s",
                tint = colors.textHi,
                modifier = Modifier.size(sideSize * 0.55f),
            )
        }

        Spacer(Modifier.width(8.dp))

        VolumeMenu(volume = volume, onVolumeChange = onVolumeChange)
    }
}

@Composable
private fun SpeedSelector(speed: Float, onSpeedChange: (Float) -> Unit) {
    val colors = KobappTheme.colors
    val type = KobappTheme.type
    val accent = colors.accAudio
    var expanded by remember { mutableStateOf(false) }
    val isNonDefault = speed != 1f
    val pillBg = if (isNonDefault) accent.copy(alpha = 0.15f) else Color.Transparent
    Box {
        Row(
            modifier = Modifier
                .focusKey("speed")
                .tvAutoGlow(cornerRadiusDp = 999f, scale = 1.03f)
                .clip(KobappTheme.shapes.pill)
                .background(pillBg)
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${speed}x",
                color = if (isNonDefault) accent else colors.textHi,
                style = type.bodySm,
                fontWeight = if (isNonDefault) FontWeight.Bold else FontWeight.Normal,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = colors.elev2,
        ) {
            val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
            for (s in speeds) {
                val active = s == speed
                DropdownMenuItem(
                    text = {
                        Text(
                            "${s}x",
                            color = if (active) accent else colors.textHi,
                            style = type.body,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    onClick = {
                        onSpeedChange(s)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun VolumeMenu(volume: Float, onVolumeChange: (Float) -> Unit) {
    val colors = KobappTheme.colors
    val accent = colors.accAudio
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.focusKey("volume").tvAutoGlow(cornerRadiusDp = 999f, scale = 1.08f),
        ) {
            Icon(
                @Suppress("DEPRECATION")
                if (volume > 0.5f) Icons.Outlined.VolumeUp
                else if (volume > 0f) Icons.Outlined.VolumeDown
                else Icons.Outlined.VolumeOff,
                "Volume",
                tint = colors.textHi,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = colors.elev2,
        ) {
            Slider(
                value = volume,
                onValueChange = onVolumeChange,
                modifier = Modifier
                    .width(180.dp)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .tvAutoGlow(cornerRadiusDp = 999f, scale = 1f),
                colors = SliderDefaults.colors(
                    thumbColor = accent,
                    activeTrackColor = accent,
                ),
            )
        }
    }
}
