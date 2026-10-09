package dev.kobbler.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kobbler.kobfocus.LocalFocusScope
import kotlinx.coroutines.flow.first

// The focus ring and the focus scale-up are both drawn outside the item's own bounds,
// so an item the lazy layout considers fully visible still looks clipped when it sits
// flush against a viewport edge.
internal val FocusEdgeMargin = 28.dp

/**
 * Keeps [margin] of slack between a focused element and the edges of the scroller it is
 * brought into view within.
 *
 * Without it a focusable is scrolled flush against the edge, where its ring and its
 * scale-up — both drawn outside its own bounds — are clipped away.
 *
 * Re-providing the spec also isolates a nested scroller from an ancestor's: the value is
 * a composition local, so a row inside a page that has installed its own spec would
 * otherwise apply that page's vertical rules to its horizontal axis.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FocusEdgeSlack(margin: Dp = FocusEdgeMargin, content: @Composable () -> Unit) {
    val marginPx = with(LocalDensity.current) { margin.toPx() }
    val spec = remember(marginPx) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) =
                focusEdgeScrollDistance(offset, size, containerSize, marginPx, hold = false)
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/**
 * Refuses every bring-into-view a scroller under it receives while [hold] reads true, and
 * applies [FocusEdgeSlack]'s margin the rest of the time.
 *
 * A page whose first focusable row already fits below a display-only header still loses
 * that header: the row's own bring-into-view lands late — after the entry scroll to the
 * top has settled — and takes it away on the viewer's first sideways press. Answering
 * with zero means no scroll is ever queued to land; scrolling back afterwards is worse,
 * for the reasons on [dev.kobbler.app.ui.detail.PageTopHold].
 *
 * Nested scrollers inherit the spec, so a row that must keep scrolling while the page is
 * held has to re-provide one of its own — wrap its lazy row in [FocusEdgeSlack].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HoldPageTop(hold: () -> Boolean, margin: Dp = FocusEdgeMargin, content: @Composable () -> Unit) {
    val marginPx = with(LocalDensity.current) { margin.toPx() }
    val current = rememberUpdatedState(hold)
    val spec = remember(marginPx) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) =
                focusEdgeScrollDistance(offset, size, containerSize, marginPx, hold = current.value())
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

internal fun focusEdgeScrollDistance(
    offset: Float,
    size: Float,
    containerSize: Float,
    margin: Float,
    hold: Boolean,
): Float {
    if (hold) return 0f
    val slack = margin.coerceAtMost((containerSize - size).coerceAtLeast(0f) / 2f)
    return when {
        offset < slack -> offset - slack
        offset + size > containerSize - slack -> offset + size - (containerSize - slack)
        else -> 0f
    }
}

// Long enough to outlast FocusScopeHost's restore pass followed by focusFirst's
// preferred-then-registration-order passes, so we only ever take over from a decision it
// has already made.
private const val EntrySettleFrames = 32

/**
 * Puts entry focus on [entryKey] — the first row of a browse screen that actually exists
 * — once that row is there.
 *
 * `preferredEntry` alone loses the race: FocusScopeHost's entry pass expires after a
 * handful of frames and every row here sits behind a fetch, so whichever eager chrome
 * (the tab strip, the A-Z bar) registered first wins it. Which row that leaves focused
 * then depends on how much of the grid the rails happen to push off screen — a rail-less
 * screen lands on the grid, a two-rail screen on a rail. This claims entry back
 * deterministically, but only after the host has had its go, and never off a content
 * target: that is either its own successful restore or the viewer's first move.
 */
@Composable
fun ClaimEntryFocus(
    entryKey: String?,
    pending: Boolean,
    isContentKey: (String) -> Boolean,
    onClaimed: () -> Unit,
) {
    val scope = LocalFocusScope.current
    LaunchedEffect(entryKey, pending) {
        if (entryKey == null || !pending) return@LaunchedEffect
        var settleFrames = EntrySettleFrames
        while (settleFrames-- > 0) {
            val settled = scope.save()
            if (settled == null) {
                withFrameNanos { }
                continue
            }
            if (isContentKey(settled)) {
                onClaimed()
                return@LaunchedEffect
            }
            break
        }
        if (scope.restore(entryKey)) onClaimed()
    }
}

internal sealed interface FocusScroll {
    data object Stay : FocusScroll

    data object Top : FocusScroll

    data class Center(val offset: Int) : FocusScroll
}

/**
 * [restingBottom] is where the focused row's bottom edge sits when the list is scrolled
 * to the top; null when that is not known.
 */
internal fun focusScroll(
    viewportStart: Int,
    viewportEnd: Int,
    margin: Int,
    itemOffset: Int?,
    itemSize: Int?,
    restingBottom: Int?,
): FocusScroll {
    if (itemOffset != null && itemSize != null &&
        itemOffset >= viewportStart + margin &&
        itemOffset + itemSize <= viewportEnd - margin
    ) {
        return FocusScroll.Stay
    }
    // Everything above the row fits on screen alongside it, so there is nothing to gain
    // by centering and a page header/hero to lose: show the page from its top instead.
    if (restingBottom != null && restingBottom <= viewportEnd - margin) return FocusScroll.Top
    // An absolute target (index + offset) rather than a measured delta on purpose: the
    // focusable's own bring-into-view scroll is already in flight when this is applied, so a
    // delta computed from the layout as it reads now overshoots by however far that scroll
    // got. Landing on an absolute target makes the two collapse into one movement instead of
    // a jump-then-correct.
    val height = itemSize ?: 0
    return FocusScroll.Center(-((viewportEnd - viewportStart - height) / 2).coerceAtLeast(0))
}

/**
 * The row key for a newly focused grid item, given the row [resolvedRow] that layoutInfo
 * reports for it and the [previous] key.
 *
 * Falling back to anything item-derived is what makes rows bounce: mid-scroll the focused
 * item is not in layoutInfo yet, so a move *along* a row mints a fresh key, and the
 * centering effect re-runs and hauls the list vertically on a left/right press. Holding
 * the previous key costs at worst one missed centering, which the focusable's own
 * bring-into-view already covers.
 */
internal fun rowKeyFor(resolvedRow: Int?, previous: Any?): Any? = resolvedRow ?: previous

/**
 * Whether an UP press on [focusedRowKey] should be eaten rather than resolved by the focus
 * search. True only on [topRowKey], the topmost focusable row on the page.
 *
 * The alphabet bar is a full-height column down the right edge, so its early letters are
 * geometrically above the first row and win an UP from it -- landing focus on an arbitrary
 * letter. Nothing above the top row is worth reaching, so the press is swallowed.
 */
internal fun swallowsUp(focusedRowKey: Any?, topRowKey: Any?): Boolean =
    focusedRowKey != null && topRowKey != null && focusedRowKey == topRowKey

@Composable
fun CenterFocusedItem(state: LazyGridState, index: Int, rowKey: Any?) {
    val margin = with(LocalDensity.current) { FocusEdgeMargin.roundToPx() }
    // A row's resting position has to be known while it is off screen, and layoutInfo
    // only reports what is visible, so it is banked whenever the list sits at the top.
    val restingBottoms = remember(state) { mutableMapOf<Int, Int>() }
    // Keyed on the row, not the item: vertical position is a property of the row, so
    // moving along one must not re-run this at all. Deriving the row from layoutInfo here
    // instead reads a scroll that is still animating and produces a visible jitter.
    LaunchedEffect(rowKey) {
        if (index < 0) return@LaunchedEffect
        val info = snapshotFlow { state.layoutInfo }.first { it.visibleItemsInfo.isNotEmpty() }
        if (state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0) {
            val rowBottoms = info.visibleItemsInfo
                .groupBy { it.row }
                .mapValues { (_, row) -> row.maxOf { it.offset.y + it.size.height } }
            info.visibleItemsInfo.forEach { restingBottoms[it.index] = rowBottoms.getValue(it.row) }
        }
        val item = info.visibleItemsInfo.firstOrNull { it.index == index }
        val rowHeight = item?.let { focused ->
            info.visibleItemsInfo.filter { it.row == focused.row }.maxOf { it.size.height }
        }
        when (
            val target = focusScroll(
                viewportStart = info.viewportStartOffset,
                viewportEnd = info.viewportEndOffset,
                margin = margin,
                itemOffset = item?.offset?.y,
                itemSize = rowHeight,
                restingBottom = restingBottoms[index],
            )
        ) {
            FocusScroll.Stay -> {}
            FocusScroll.Top -> state.animateScrollToItem(0)
            is FocusScroll.Center -> state.animateScrollToItem(index, target.offset)
        }
    }
}

/**
 * [topRow] is the first focusable row on the page. It always resolves to the top rather
 * than centring: everything above it — a hero, a title block — is display-only, and
 * centring the first row a viewer lands on scrolls that away before they have pressed
 * anything. Pass -1 when the page has nothing above its first row worth keeping.
 */
@Composable
fun CenterFocusedItem(state: LazyListState, index: Int, topRow: Int = -1) {
    val margin = with(LocalDensity.current) { FocusEdgeMargin.roundToPx() }
    val restingBottoms = remember(state) { mutableMapOf<Int, Int>() }
    LaunchedEffect(index) {
        if (index < 0) return@LaunchedEffect
        if (index == topRow) {
            state.animateScrollToItem(0)
            return@LaunchedEffect
        }
        val info = snapshotFlow { state.layoutInfo }.first { it.visibleItemsInfo.isNotEmpty() }
        if (state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0) {
            info.visibleItemsInfo.forEach { restingBottoms[it.index] = it.offset + it.size }
        }
        val item = info.visibleItemsInfo.firstOrNull { it.index == index }
        when (
            val target = focusScroll(
                viewportStart = info.viewportStartOffset,
                viewportEnd = info.viewportEndOffset,
                margin = margin,
                itemOffset = item?.offset,
                itemSize = item?.size,
                restingBottom = restingBottoms[index],
            )
        ) {
            FocusScroll.Stay -> {}
            FocusScroll.Top -> state.animateScrollToItem(0)
            is FocusScroll.Center -> state.animateScrollToItem(index, target.offset)
        }
    }
}
