package dev.kobbler.app.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.LocalFormFactor

// systemBars + displayCutout, deliberately not safeDrawing: safeDrawing folds in the
// IME, so chrome anchored to it jumps whenever the keyboard opens. The cutout half
// only matters in landscape, where it lands on a long edge and statusBars/navigationBars
// report nothing for it.
val kobChromeInsets: WindowInsets
    @Composable
    get() = WindowInsets.systemBars.union(WindowInsets.displayCutout)

// For content inside a scrolling body, where the chrome above it is an overlay rather
// than a sibling: padding the scroller would stop the page passing under the bar, so the
// inset has to be spent on the first spacer instead.
val kobTopInset: Dp
    @Composable
    get() = kobChromeInsets.asPaddingValues().calculateTopPadding()

@Composable
fun Modifier.safeTopPadding(): Modifier =
    windowInsetsPadding(kobChromeInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))

@Composable
fun Modifier.safeHorizontalPadding(): Modifier =
    windowInsetsPadding(kobChromeInsets.only(WindowInsetsSides.Horizontal))

@Composable
fun Modifier.safeBottomPadding(): Modifier =
    windowInsetsPadding(kobChromeInsets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))

@Composable
fun Modifier.safeContentPadding(): Modifier = windowInsetsPadding(kobChromeInsets)

/**
 * Room for the on-screen keyboard, except on a television, which has none to make room
 * for: the leanback IME is its own window drawn over the app.
 *
 * It is also not to be trusted. The inset it reports on the way in is not always
 * withdrawn on the way out — the PIN entered at login leaves 240dp of it behind — and a
 * page that pads for it then spends a third of the screen on a keyboard that closed
 * minutes ago. The guide showed that as a grid clipped to a single row of channels.
 */
@Composable
fun Modifier.imePaddingUnlessTv(): Modifier =
    if (LocalFormFactor.current == FormFactor.TV) this else imePadding()

val kobKeyboardShown: Boolean
    @Composable
    get() = WindowInsets.ime.getBottom(LocalDensity.current) > 0
