package dev.kobbler.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import dev.kobbler.app.theme.KobappTheme

// How much pinned chrome sits over the top of the page here: the phone bar, the status bar,
// a header. Zero where nothing is pinned over the page.
val LocalTopChromeOverlap = compositionLocalOf { 0.dp }

// The page is laid out below the chrome so its skeletons and empty states stay clear of it;
// a scroller wearing this grows back up under the chrome to the top of the screen, and must
// pad its content top by LocalTopChromeOverlap so it still opens below it.
@Composable
fun Modifier.scrollsUnderTopChrome(): Modifier {
    val overlap = LocalTopChromeOverlap.current
    if (overlap == 0.dp) return this
    return layout { measurable, constraints ->
        val px = overlap.roundToPx()
        val placeable = measurable.measure(constraints.offset(vertical = px))
        layout(placeable.width, (placeable.height - px).coerceAtLeast(0)) { placeable.place(0, -px) }
    }
}

@Composable
fun rememberTopChromeShade(): Brush {
    val bg = KobappTheme.colors.bg
    return remember(bg) {
        Brush.verticalGradient(0f to bg.copy(alpha = 0.85f), 0.55f to bg.copy(alpha = 0.55f), 1f to Color.Transparent)
    }
}

// The bar-less pages' only chrome is the status bar, which any taller chrome already covers.
@Composable
fun UnderStatusBar(content: @Composable () -> Unit) {
    val outer = LocalTopChromeOverlap.current
    val overlap = maxOf(outer, kobTopInset)
    Box(Modifier.padding(top = overlap - outer)) {
        CompositionLocalProvider(LocalTopChromeOverlap provides overlap, content = content)
    }
}

private class ScrolledUnder : NestedScrollConnection {
    var px by mutableFloatStateOf(0f)

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        px = (px - consumed.y).coerceAtLeast(0f)
        return Offset.Zero
    }
}

private enum class PinnedSlot { Header, Content }

// One shade over all the pinned chrome: a header pinned inside another's page reaches up to
// the top of the screen too, so it draws the shade for both and the outer one stands down.
private class PinnedShade {
    var nested by mutableStateOf(false)
}

private val LocalPinnedShade = staticCompositionLocalOf<PinnedShade?> { null }

// A header that stays put while its page scrolls up beneath it and the chrome above it. Its
// shade only comes in once something has scrolled under it: at rest it would grey out the
// top of the page's picture.
@Composable
fun PinnedHeader(
    header: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val overlap = LocalTopChromeOverlap.current
    if (overlap == 0.dp) {
        Column(modifier) {
            header()
            Column(Modifier.weight(1f), content = content)
        }
        return
    }
    val bg = KobappTheme.colors.bg
    // Heavier than the bar's: a header carries chips and fields, not three icons.
    val shade = remember(bg) {
        Brush.verticalGradient(0f to bg.copy(alpha = 0.92f), 0.8f to bg.copy(alpha = 0.82f), 1f to Color.Transparent)
    }
    val scrolled = remember { ScrolledUnder() }
    val ownShade = remember { PinnedShade() }
    val parentShade = LocalPinnedShade.current
    DisposableEffect(parentShade) {
        parentShade?.nested = true
        onDispose { parentShade?.nested = false }
    }
    val fadePx = with(LocalDensity.current) { 96.dp.toPx() }
    SubcomposeLayout(modifier.scrollsUnderTopChrome().nestedScroll(scrolled)) { constraints ->
        val loose = constraints.copy(minHeight = 0)
        val headerPlaceable = subcompose(PinnedSlot.Header) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .drawBehind { if (!ownShade.nested) drawRect(shade, alpha = (scrolled.px / fadePx).coerceIn(0f, 1f)) }
                    .padding(top = overlap),
            ) { header() }
        }.single().measure(loose.copy(maxHeight = Constraints.Infinity))
        val top = headerPlaceable.height
        val headerOverlap = top.toDp()
        val contentPlaceable = subcompose(PinnedSlot.Content) {
            CompositionLocalProvider(LocalTopChromeOverlap provides headerOverlap, LocalPinnedShade provides ownShade) {
                Column(Modifier.fillMaxSize(), content = content)
            }
        }.single().measure(loose.copy(maxHeight = (constraints.maxHeight - top).coerceAtLeast(0)))
        layout(constraints.maxWidth, constraints.maxHeight) {
            contentPlaceable.place(0, top)
            headerPlaceable.place(0, 0)
        }
    }
}
