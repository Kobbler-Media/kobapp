package dev.kobbler.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import dev.kobbler.app.platform.Settings
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce

private const val WindowKey = "window"

data class SavedWindow(
    val placement: WindowPlacement,
    val x: Int?,
    val y: Int?,
    val width: Int,
    val height: Int,
) {
    fun encode(): String = listOf(placement.name, x ?: "", y ?: "", width, height).joinToString(",")

    // A monitor that was unplugged since the last run would reopen the window where
    // nobody can see or drag it; the top edge is what has to be reachable.
    fun fitTo(screens: List<Rectangle>): SavedWindow {
        val largest = screens.maxByOrNull { it.width.toLong() * it.height } ?: return this
        val w = width.coerceAtMost(largest.width)
        val h = height.coerceAtMost(largest.height)
        val visible = x != null && y != null && screens.any { it.intersects(Rectangle(x, y, w, 48)) }
        return copy(x = x.takeIf { visible }, y = y.takeIf { visible }, width = w, height = h)
    }

    companion object {
        fun decode(raw: String?): SavedWindow? {
            val parts = raw?.split(',')?.takeIf { it.size == 5 } ?: return null
            val placement = WindowPlacement.entries.firstOrNull { it.name == parts[0] } ?: return null
            val (x, y) = parts.subList(1, 3).map { if (it.isEmpty()) null else it.toIntOrNull() ?: return null }
            val width = parts[3].toIntOrNull() ?: return null
            val height = parts[4].toIntOrNull() ?: return null
            return SavedWindow(placement, x, y, width, height)
        }
    }
}

private fun screenBounds(): List<Rectangle> =
    if (GraphicsEnvironment.isHeadless()) emptyList()
    else GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration.bounds }

@Composable
fun rememberPersistedWindowState(): WindowState {
    val saved = SavedWindow.decode(Settings.getString(WindowKey))?.fitTo(screenBounds())
    return rememberWindowState(
        // Fullscreen is a mode the viewer enters, not a place the window lives.
        placement = saved?.placement?.takeIf { it != WindowPlacement.Fullscreen } ?: WindowPlacement.Maximized,
        size = saved?.let { DpSize(it.width.dp, it.height.dp) } ?: DpSize(1280.dp, 800.dp),
        position = if (saved?.x != null && saved.y != null) WindowPosition(saved.x.dp, saved.y.dp) else WindowPosition.PlatformDefault,
    )
}

@OptIn(FlowPreview::class)
@Composable
fun PersistWindowState(state: WindowState, isFullscreen: Boolean) {
    LaunchedEffect(state, isFullscreen) {
        // While fullscreen, the floating size and position are whatever the pre-fullscreen
        // restore will put back, so there is nothing new to record.
        if (isFullscreen) return@LaunchedEffect
        var last = SavedWindow.decode(Settings.getString(WindowKey))
        snapshotFlow { Triple(state.placement, state.position, state.size) }
            .debounce(500)
            .collect { (placement, pos, size) ->
                // Only a floating window's geometry is its own: maximized reports the
                // screen's, and keeping that would make un-maximizing a no-op next run.
                val next = if (placement == WindowPlacement.Floating) {
                    SavedWindow(
                        placement = placement,
                        x = pos.takeIf { it.isSpecified }?.x?.value?.toInt(),
                        y = pos.takeIf { it.isSpecified }?.y?.value?.toInt(),
                        width = size.width.value.toInt(),
                        height = size.height.value.toInt(),
                    )
                } else {
                    last?.copy(placement = placement) ?: SavedWindow(placement, null, null, 1280, 800)
                }
                last = next
                Settings.putString(WindowKey, next.encode())
            }
    }
}
