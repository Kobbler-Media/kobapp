package dev.kobbler.kobplayer

import android.os.Build
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
actual fun rememberPlayer(config: PlayerConfig): Player {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val player = remember {
        AndroidPlayer(context.applicationContext, scope.coroutineContext, config)
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    return player
}

actual fun createPlayer(config: PlayerConfig, parentContext: kotlin.coroutines.CoroutineContext): Player {
    throw UnsupportedOperationException("Use rememberPlayer() in Compose or platform-specific APIs on Android")
}

@Composable
actual fun VideoSurface(
    player: Player,
    modifier: Modifier,
    overlay: @Composable (Player) -> Unit,
) {
    val android = player as AndroidPlayer
    val aspectRatio by player.aspectRatio.collectAsState()
    val videoSize by player.videoSize.collectAsState()

    Box(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        // The surface is sized to the picture and libVLC told to fill it (onSurfaceSize):
        // left to place the picture itself it trusts decoder geometry some chipsets pad.
        val (vw, vh) = videoSize
        val surfaceModifier = Modifier.layout { measurable, constraints ->
            val (w, h) = videoFrameSize(aspectRatio, vw, vh, constraints.maxWidth, constraints.maxHeight)
            val placeable = measurable.measure(
                constraints.copy(minWidth = w, maxWidth = w, minHeight = h, maxHeight = h)
            )
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeable.place((constraints.maxWidth - w) / 2, (constraints.maxHeight - h) / 2)
            }
        }

        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    // A SurfaceView's surface is destroyed and rebuilt with view
                    // visibility by default, so it arrives later than the stream that
                    // needs it. Tying it to attachment instead is framework code — the
                    // same on every chipset — and is what Media3's own PlayerView does.
                    if (Build.VERSION.SDK_INT >= 34) {
                        setSurfaceLifecycle(SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT)
                    }
                    holder.addCallback(object : android.view.SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                            // Attached immediately, preload included: a decoder is chosen
                            // when the stream opens and cannot be handed a window that
                            // turns up afterwards.
                            android.pendingSurface = holder
                            android.attachVoutIfReady()
                            android.onSurfaceReady(true)
                        }

                        override fun surfaceChanged(
                            holder: android.view.SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int,
                        ) {
                            android.onSurfaceSize(width, height)
                        }

                        override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                            android.onSurfaceReady(false)
                            android.pendingSurface = null
                            android.detachVout()
                        }
                    })
                }
            },
            // Without it Compose discards and recreates the view inside any reusable
            // container, and a recreated SurfaceView is a destroyed surface.
            onReset = {},
            modifier = surfaceModifier,
        )

        overlay(player)
    }
}

@Composable
actual fun rememberPlayerAsync(config: PlayerConfig): Player? {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var player by remember { mutableStateOf<Player?>(null) }

    LaunchedEffect(config) {
        val appContext = context.applicationContext
        player = withContext(Dispatchers.IO) {
            AndroidPlayer(appContext, scope.coroutineContext, config)
        }
    }

    DisposableEffect(Unit) {
        onDispose { player?.release() }
    }

    return player
}
