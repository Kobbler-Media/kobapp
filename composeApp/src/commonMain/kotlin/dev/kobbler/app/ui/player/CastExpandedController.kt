package dev.kobbler.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kobbler.app.platform.BackHandler
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.FadeInImage
import dev.kobbler.app.ui.kobChromeInsets
import dev.kobbler.kobfocus.focusKeyGroup

@Composable
fun CastExpandedController(
    session: CastSessionDto,
    subtitle: String?,
    artUrl: String?,
    onPlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onDisconnect: () -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
    onSelectSubtitle: (Int?) -> Unit = {},
    onSelectAudio: (Int) -> Unit = {},
) {
    val colors = KobappTheme.colors
    val type = KobappTheme.type
    BackHandler(onBack = onCollapse)
    Box(
        modifier = modifier
            .focusKeyGroup("cast-expanded")
            .fillMaxSize()
            .background(colors.bg)
            .pointerInput(Unit) { detectTapGestures {} }
            .windowInsetsPadding(kobChromeInsets),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CastTransportButton(
                    key = "collapse",
                    icon = Icons.Rounded.KeyboardArrowDown,
                    description = "Collapse",
                    tint = colors.textHi,
                    iconSize = 28.dp,
                    onClick = onCollapse,
                )
                Text(
                    text = castingLabel(session.deviceName, session.connecting),
                    style = type.caption,
                    color = colors.accCal,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.size(40.dp))
            }

            Spacer(Modifier.height(24.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.elev2),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Cast, contentDescription = null, tint = colors.accCal, modifier = Modifier.size(48.dp))
                if (artUrl != null) {
                    FadeInImage(model = artUrl, contentDescription = null, modifier = Modifier.fillMaxSize())
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                text = session.mediaTitle.ifBlank { "Now casting" },
                style = type.h2,
                color = colors.textHi,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = type.body,
                    color = colors.textLo,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(20.dp))
            Box(Modifier.height(40.dp), contentAlignment = Alignment.Center) {
                session.skip?.let { CastSkipButton(it, onSeekTo) }
            }
            Spacer(Modifier.height(8.dp))
            CastSeekRow(
                positionMs = session.positionMs,
                durationMs = session.durationMs,
                onSeekTo = onSeekTo,
            )

            Spacer(Modifier.height(20.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                CastTransportButton(
                    key = "back10",
                    icon = Icons.Rounded.Replay10,
                    description = "Back 10 seconds",
                    tint = colors.textHi,
                    iconSize = 32.dp,
                    size = 56.dp,
                    onClick = { onSeekTo((session.positionMs - 10_000).coerceAtLeast(0)) },
                )
                CastTransportButton(
                    key = "playpause",
                    icon = if (session.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    description = if (session.isPlaying) "Pause" else "Play",
                    tint = colors.textHi,
                    iconSize = 48.dp,
                    size = 72.dp,
                    onClick = onPlayPause,
                )
                CastTransportButton(
                    key = "fwd30",
                    icon = Icons.Rounded.Forward30,
                    description = "Forward 30 seconds",
                    tint = colors.textHi,
                    iconSize = 32.dp,
                    size = 56.dp,
                    onClick = {
                        val end = session.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
                        onSeekTo((session.positionMs + 30_000).coerceAtMost(end))
                    },
                )
            }

            session.tracks?.let {
                Spacer(Modifier.height(24.dp))
                CastTrackMenus(it, onSelectSubtitle, onSelectAudio)
            }

            Spacer(Modifier.height(32.dp))
            Text(
                text = "Stop casting",
                style = type.body,
                color = colors.textLo,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .clickable(onClick = onDisconnect)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
    }
}
