package dev.kobbler.app.ui.player

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.CastConnected
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.kobbler.app.platform.CastDevice
import dev.kobbler.app.platform.CastRouter
import dev.kobbler.app.platform.PlaybackTarget
import dev.kobbler.app.platform.PlaybackTargetKind
import dev.kobbler.app.platform.castManager
import kotlinx.coroutines.launch

val LocalCastRouter = staticCompositionLocalOf<CastRouter> { castManager() }

internal fun playOnVisible(targets: List<PlaybackTarget>, device: CastDevice?): Boolean =
    targets.isNotEmpty() || device != null

private val SoundOnly = setOf(PlaybackTargetKind.Speaker, PlaybackTargetKind.Group)

// A receiver fetches its own stream, so a speaker can't take only the sound of a video shown here.
internal fun castableFor(targets: List<PlaybackTarget>, video: Boolean): List<PlaybackTarget> =
    if (video) targets.filterNot { it.kind in SoundOnly } else targets

/**
 * Hand playback to another device, or control the one it went to.
 *
 * [onOpenChange] reports the picker or controller being up, so a host that hides its
 * chrome on a timer can hold it: timing out disposes this button, and the dialog with it.
 */
@Composable
fun PlayOnButton(
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    audio: Boolean = false,
    onOpenChange: (Boolean) -> Unit = {},
) {
    val router = LocalCastRouter.current
    val gps = LocalGlobalPlayerState.current
    val video = !audio && gps?.isActive == true && gps.mediaType in CastableTypes
    val found by router.targets.collectAsState(emptyList())
    val targets = castableFor(found, video)
    val device by router.device.collectAsState()
    var open by remember { mutableStateOf(false) }
    val reportOpen by rememberUpdatedState(onOpenChange)
    LaunchedEffect(open) { reportOpen(open) }
    DisposableEffect(Unit) { onDispose { reportOpen(false) } }

    if (!open && !playOnVisible(targets, device)) return
    val connected = device?.connecting == false
    val scope = rememberCoroutineScope()
    IconButton(onClick = { open = true }, modifier = modifier) {
        Icon(
            if (connected) Icons.Outlined.CastConnected else Icons.Outlined.Cast,
            contentDescription = "Play on",
            tint = if (tint == Color.Unspecified) LocalContentColor.current else tint,
        )
    }
    if (!open) return
    val close = { open = false }
    if (connected) {
        CastControllerDialog(
            deviceName = device?.name.orEmpty(),
            session = gps?.castSession,
            posterUrl = gps?.posterUrl,
            backdropUrl = gps?.backdropUrl,
            onPlayPause = gps?.onCastPlayPause,
            onStop = {
                close()
                scope.launch { router.stop() }
            },
            onDismiss = close,
        )
    } else {
        LaunchedEffect(router) { router.scanActively() }
        CastPickerDialog(
            targets = targets,
            onPick = { picked ->
                close()
                scope.launch { router.connect(picked) }
            },
            onDismiss = close,
        )
    }
}
