package dev.kobbler.app.ui.components

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent

// Skipping the draw is not enough on its own: an animation inside a child layer still
// re-records that layer, and every re-record repaints the window.
val LocalCoveredByPlayer = compositionLocalOf { false }

fun Modifier.hiddenWhenCovered(covered: Boolean): Modifier =
    drawWithContent { if (!covered) drawContent() }
