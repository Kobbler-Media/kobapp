package dev.kobbler.app.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop

@Immutable
data class Spotlight(
    val backdropUrl: String? = null,
    val posterUrl: String? = null,
    val title: String? = null,
) {
    val hasArt: Boolean get() = !backdropUrl.isNullOrBlank() || !posterUrl.isNullOrBlank()
    val hasBackdrop: Boolean get() = !backdropUrl.isNullOrBlank()
}

/**
 * The card a touch page settles on, from [candidates] in reading order: a real backdrop
 * over a softened poster, and anything over what is already showing, so a scroll always
 * changes the picture when it can.
 */
fun pickSpotlight(candidates: List<Spotlight>, current: Spotlight?): Spotlight? {
    val withArt = candidates.filter { it.hasArt }
    val best = withArt.filter { it.hasBackdrop }.ifEmpty { withArt }
    return best.firstOrNull { it != current } ?: best.firstOrNull()
}

// Long enough that stepping along a row does not swap the backdrop under every press,
// short enough to feel like the picture is following focus rather than lagging it.
private const val SpotlightSettleMs = 250L

/**
 * What a page's backdrop shows. [point] is cheap and may run on every focus move or
 * hover; [shown] only follows once the pointing has settled, because each change is a
 * full-screen image load and decode.
 */
@Stable
class SpotlightState(initial: Spotlight?) {
    internal var pointedAt by mutableStateOf(initial)
    var shown by mutableStateOf(initial)
        internal set

    /** What the page opens on, and goes back to at the top. */
    internal var home: Spotlight? = initial

    fun point(spot: Spotlight) {
        if (spot.hasArt) pointedAt = spot
    }

    fun goHome() {
        home?.let(::point)
    }
}

@Composable
fun rememberSpotlightState(initial: Spotlight?): SpotlightState {
    val state = remember { SpotlightState(initial) }
    LaunchedEffect(initial) {
        state.home = initial
        if (state.shown == null && initial != null) {
            state.pointedAt = initial
            state.shown = initial
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.pointedAt }.collectLatest { spot ->
            delay(SpotlightSettleMs)
            state.shown = spot
        }
    }
    return state
}

fun Modifier.onHoverEnter(action: () -> Unit): Modifier = pointerInput(action) {
    awaitPointerEventScope {
        while (true) {
            if (awaitPointerEvent().type == PointerEventType.Enter) action()
        }
    }
}

fun Modifier.spotlightOn(state: SpotlightState?, spot: () -> Spotlight): Modifier =
    if (state == null) this
    else onHoverEnter { state.point(spot()) }.onFocusChanged { if (it.isFocused) state.point(spot()) }

// Long enough that a fling has come to rest rather than paused between frames.
private const val ScrollSettleMs = 300L

/**
 * Touch has nothing to point with, so each time a scroll comes to rest the backdrop moves
 * to a card along the bottom of the screen — the ones the eye has just scrolled to — and
 * back to the page's opening picture once the page is at its top again. The first
 * composition is skipped: until the viewer scrolls, the opening picture holds.
 *
 * Settling is read off [position] rather than `isScrollInProgress`: a short fling can start
 * and stop between two snapshot reads, and the conflated flow then never sees it end.
 */
@Composable
fun SpotlightOnScroll(
    state: SpotlightState,
    position: () -> Any,
    atTop: () -> Boolean,
    bottomRow: () -> List<Spotlight>,
) {
    val currentBottomRow by rememberUpdatedState(bottomRow)
    val currentAtTop by rememberUpdatedState(atTop)
    LaunchedEffect(state) {
        snapshotFlow(position).drop(1).collectLatest {
            delay(ScrollSettleMs)
            if (currentAtTop()) state.goHome()
            else pickSpotlight(currentBottomRow(), state.shown)?.let(state::point)
        }
    }
}

/**
 * A swipe along one rail is a look at that rail, so when it settles the backdrop follows
 * the first card it left fully on screen.
 */
@Composable
fun SpotlightOnRowScroll(state: SpotlightState?, rowState: LazyListState, spotlightAt: (index: Int) -> Spotlight?) {
    if (state == null) return
    val currentSpotlightAt by rememberUpdatedState(spotlightAt)
    LaunchedEffect(state, rowState) {
        snapshotFlow { rowState.firstVisibleItemIndex to rowState.firstVisibleItemScrollOffset }
            .drop(1)
            .collectLatest {
                delay(ScrollSettleMs)
                val info = rowState.layoutInfo
                val whole = info.visibleItemsInfo
                    .filter { it.offset >= info.viewportStartOffset && it.offset + it.size <= info.viewportEndOffset }
                    .ifEmpty { info.visibleItemsInfo }
                pickSpotlight(whole.mapNotNull { currentSpotlightAt(it.index) }, state.shown)?.let(state::point)
            }
    }
}

/** Keys of the lowest row of [LazyGridState] that is fully on screen, or the lowest row at all when none is. */
fun LazyGridState.bottomRowKeys(): List<Any> {
    val info = layoutInfo
    val items = info.visibleItemsInfo.ifEmpty { return emptyList() }
    val whole = items.filter { it.offset.y + it.size.height <= info.viewportEndOffset }.ifEmpty { items }
    val row = whole.maxOf { it.row }
    return whole.filter { it.row == row }.map { it.key }
}

/** Key of the lowest item of [LazyListState] that is fully on screen, or the lowest at all when none is. */
fun LazyListState.bottomItemKey(): Any? {
    val info = layoutInfo
    val items = info.visibleItemsInfo.ifEmpty { return null }
    return (items.lastOrNull { it.offset + it.size <= info.viewportEndOffset } ?: items.last()).key
}
