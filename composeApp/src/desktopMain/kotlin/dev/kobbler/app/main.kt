package dev.kobbler.app

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import dev.kobbler.app.platform.linux.installX11HorizontalScroll
import dev.kobbler.app.ui.player.PlaybackExit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.skia.Image

fun main() {
    installX11HorizontalScroll()
    app()
}

private fun app() = application {
    val icon = remember {
        val bytes = Thread.currentThread().contextClassLoader
            .getResourceAsStream("icon.png")?.readBytes()
        bytes?.let { BitmapPainter(Image.makeFromEncoded(it).toComposeImageBitmap()) }
    }

    val windowState = rememberPersistedWindowState()
    var isFullscreen by remember { mutableStateOf(false) }
    PersistWindowState(windowState, isFullscreen)

    // Save pre-fullscreen state so we can restore it exactly
    var savedPlacement by remember { mutableStateOf(WindowPlacement.Maximized) }
    var savedSize by remember { mutableStateOf(DpSize(1280.dp, 800.dp)) }
    var savedPosition: WindowPosition by remember { mutableStateOf(WindowPosition.PlatformDefault) }

    fun toggleFullscreen() {
        if (!isFullscreen) {
            savedPlacement = windowState.placement
            savedSize = windowState.size
            savedPosition = windowState.position
        }
        isFullscreen = !isFullscreen
    }

    // First composition is not a fullscreen exit: restoring then would throw away the
    // window's remembered size and position.
    var wasFullscreen by remember { mutableStateOf(false) }
    LaunchedEffect(isFullscreen) {
        if (isFullscreen) {
            wasFullscreen = true
            windowState.placement = WindowPlacement.Fullscreen
        } else if (wasFullscreen) {
            // Fullscreen → Maximized is broken on Linux (JB issue #4006).
            // Go to Floating first with the saved size/position pre-applied
            // so the brief intermediate state is the right size, then maximize
            // if that was the previous placement.
            windowState.size = savedSize
            windowState.position = savedPosition
            windowState.placement = WindowPlacement.Floating
            if (savedPlacement == WindowPlacement.Maximized) {
                delay(50)
                windowState.placement = WindowPlacement.Maximized
            }
        }
    }

    val closeScope = rememberCoroutineScope()
    Window(
        onCloseRequest = {
            closeScope.launch {
                PlaybackExit.flush()
                exitApplication()
            }
        },
        title = "Kobbler",
        state = windowState,
        icon = icon,
        onPreviewKeyEvent = { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.F11) {
                toggleFullscreen()
                true
            } else if (event.type == KeyEventType.KeyDown && event.key == Key.Escape && isFullscreen) {
                toggleFullscreen()
                true
            } else false
        },
    ) {
        App(
            onToggleFullscreen = ::toggleFullscreen,
            isFullscreen = isFullscreen,
        )
    }
}
