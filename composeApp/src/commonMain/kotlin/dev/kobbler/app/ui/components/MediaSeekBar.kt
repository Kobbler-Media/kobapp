package dev.kobbler.app.ui.components

// MediaSeekBar has moved to kobplayer library: dev.kobbler.kobplayer.ui.MediaSeekBar
// This file kept for existing import compatibility.

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun MediaSeekBar(
    progress: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onSeeking: ((Float) -> Unit)? = null,
    activeColor: Color = Color.White,
    inactiveColor: Color = Color.White.copy(alpha = 0.2f),
    bufferedColor: Color = Color.White.copy(alpha = 0.35f),
    bufferedProgress: Float = 0f,
    chapterMarkers: List<Float> = emptyList(),
    idleTrackHeight: Dp = 3.dp,
    activeTrackHeight: Dp = 6.dp,
    thumbRadius: Dp = 7.dp,
) {
    dev.kobbler.kobplayer.ui.MediaSeekBar(
        progress = progress, onSeek = onSeek, modifier = modifier,
        onSeeking = onSeeking, activeColor = activeColor, inactiveColor = inactiveColor,
        bufferedColor = bufferedColor, bufferedProgress = bufferedProgress,
        chapterMarkers = chapterMarkers, idleTrackHeight = idleTrackHeight,
        activeTrackHeight = activeTrackHeight, thumbRadius = thumbRadius,
    )
}
