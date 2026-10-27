package dev.kobbler.app.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

enum class Edge { Top, Bottom, Start, End }

/** A hairline on one side only — for chrome whose other sides run off the screen. */
fun Modifier.edgeLine(edge: Edge, color: Color, width: Dp = 1.dp): Modifier = drawBehind {
    val w = width.toPx()
    val rtl = layoutDirection == LayoutDirection.Rtl
    val (origin, extent) = when (edge) {
        Edge.Top -> Offset.Zero to Size(size.width, w)
        Edge.Bottom -> Offset(0f, size.height - w) to Size(size.width, w)
        Edge.Start, Edge.End -> {
            val atRight = (edge == Edge.End) != rtl
            Offset(if (atRight) size.width - w else 0f, 0f) to Size(w, size.height)
        }
    }
    drawRect(color, origin, extent)
}
