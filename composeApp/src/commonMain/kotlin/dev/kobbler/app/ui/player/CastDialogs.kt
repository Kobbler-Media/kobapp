package dev.kobbler.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.CastConnected
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material.icons.outlined.SpeakerGroup
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kobbler.app.platform.PlaybackTarget
import dev.kobbler.app.platform.PlaybackTargetKind
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.ChoiceRow
import dev.kobbler.app.ui.components.FadeInImage
import dev.kobbler.app.ui.components.KobButton
import dev.kobbler.app.ui.components.KobButtonStyle
import dev.kobbler.kobfocus.focusKey

private val PlaybackTargetKind.icon: ImageVector
    get() = when (this) {
        PlaybackTargetKind.Tv -> Icons.Outlined.Tv
        PlaybackTargetKind.Speaker -> Icons.Outlined.Speaker
        PlaybackTargetKind.Group -> Icons.Outlined.SpeakerGroup
        PlaybackTargetKind.Other -> Icons.Outlined.Cast
    }

@Composable
fun CastPickerDialog(
    targets: List<PlaybackTarget>,
    onPick: (PlaybackTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = KobappTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Play on", color = colors.textHi, style = KobappTheme.type.h3) },
        text = {
            if (targets.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = colors.accCal, strokeWidth = 2.dp)
                    Spacer(Modifier.width(14.dp))
                    Text("Looking for devices…", color = colors.textMd, style = KobappTheme.type.body)
                }
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    targets.forEachIndexed { i, target ->
                        ChoiceRow(
                            label = target.name,
                            detail = target.protocol.label,
                            selected = false,
                            accent = colors.accCal,
                            focusKeyName = "cast.route.${target.id}",
                            onClick = { onPick(target) },
                            preferredEntry = i == 0,
                            icon = target.kind.icon,
                        )
                    }
                }
            }
        },
        confirmButton = {
            KobButton(onClick = onDismiss, style = KobButtonStyle.Ghost, modifier = Modifier.focusKey("cast.cancel")) {
                Text("Cancel", color = colors.textHi, style = KobappTheme.type.body)
            }
        },
        containerColor = colors.elev1,
        titleContentColor = colors.textHi,
        textContentColor = colors.textMd,
    )
}

@Composable
fun CastControllerDialog(
    deviceName: String,
    session: CastSessionDto?,
    posterUrl: String?,
    backdropUrl: String?,
    onPlayPause: (() -> Unit)?,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = KobappTheme.colors
    val type = KobappTheme.type
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.CastConnected, null, tint = colors.accCal, modifier = Modifier.size(28.dp)) },
        title = { Text(deviceName, color = colors.textHi, style = type.h3, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            if (session == null) {
                Text("Nothing playing yet", color = colors.textMd, style = type.body)
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.elev2)
                        .padding(10.dp),
                ) {
                    val art = castArt(posterUrl, backdropUrl)
                    Box(
                        Modifier
                            .testTag(CastArtTag)
                            .height(if (art?.tall == true) 66.dp else 54.dp)
                            .aspectRatio(art?.aspect ?: (16f / 9f))
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.elev1),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (art != null) {
                            FadeInImage(art.url, contentDescription = null, modifier = Modifier.fillMaxSize())
                        } else {
                            Icon(Icons.Outlined.Cast, null, tint = colors.accCal, modifier = Modifier.size(22.dp))
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            session.mediaTitle.ifBlank { "Now casting" },
                            color = colors.textHi,
                            style = type.body,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!session.subtitle.isNullOrBlank()) {
                            Spacer(Modifier.height(2.dp))
                            Text(session.subtitle, color = colors.textLo, style = type.caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (onPlayPause != null) {
                        CastTransportButton(
                            key = "cast.playpause",
                            icon = if (session.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            description = if (session.isPlaying) "Pause" else "Play",
                            tint = colors.textHi,
                            iconSize = 26.dp,
                            onClick = onPlayPause,
                        )
                    }
                }
            }
        },
        confirmButton = {
            KobButton(
                onClick = onStop,
                style = KobButtonStyle.Accent,
                accent = colors.err,
                modifier = Modifier.focusKey("cast.stop"),
            ) { Text("Stop casting", color = colors.bg, style = type.body) }
        },
        dismissButton = {
            KobButton(onClick = onDismiss, style = KobButtonStyle.Ghost, modifier = Modifier.focusKey("cast.close")) {
                Text("Close", color = colors.textHi, style = type.body)
            }
        },
        containerColor = colors.elev1,
        titleContentColor = colors.textHi,
        textContentColor = colors.textMd,
    )
}
