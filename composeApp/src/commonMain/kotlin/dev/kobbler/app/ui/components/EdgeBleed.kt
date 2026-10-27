package dev.kobbler.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.theme.LocalFormFactor

/**
 * Lets a horizontally scrolling row inside a gutter-padded page extend [start] and [end]
 * past its own edges while still reporting the un-bled width to its parent, so the
 * surrounding vertical layout is unaffected.
 *
 * Pair it with content padding of the same amounts: the row then still *starts* on the
 * gutter, scrolls its items out to the physical screen edge instead of stopping dead at the
 * gutter, and keeps a trailing gutter once the list bottoms out.
 */
fun Modifier.bleedHorizontally(start: Dp, end: Dp = start): Modifier =
    if (start <= 0.dp && end <= 0.dp) this else layout { measurable, constraints ->
        val extra = start.roundToPx() + end.roundToPx()
        val placeable = measurable.measure(
            constraints.copy(
                minWidth = constraints.minWidth + extra,
                maxWidth = if (constraints.maxWidth == Constraints.Infinity) {
                    Constraints.Infinity
                } else {
                    constraints.maxWidth + extra
                },
            ),
        )
        layout((placeable.width - extra).coerceAtLeast(0), placeable.height) {
            placeable.place(-start.roundToPx(), 0)
        }
    }

/**
 * How far a carousel may bleed out of the page gutter. Touch gets the whole gutter, so rows
 * run to the screen edge; TV keeps its overscan margin and bleeds only far enough for a
 * focus ring, which is drawn outside the card's bounds and was otherwise clipped.
 */
@Composable
fun rowEdgeBleed(): Dp = with(KobappTheme.dimens) {
    if (LocalFormFactor.current == FormFactor.Phone) pageGutterPhone else focusBleed
}

@Composable
fun pageGutter(): Dp = with(KobappTheme.dimens) {
    when (LocalFormFactor.current) {
        FormFactor.TV -> pageGutterTv
        FormFactor.Phone -> pageGutterPhone
        FormFactor.Desktop -> pageGutter
    }
}

/** Room at the top of a list for its first row's focus ring, which the list would clip. */
@Composable
fun listTopFocusRoom(): Dp = with(KobappTheme.dimens) {
    if (LocalFormFactor.current == FormFactor.Phone) 0.dp else focusBleed
}
