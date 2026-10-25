package dev.kobbler.app.ui.detail

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable

/** A detail page's vertical scroll, whether the page is one tall column or a lazy list. */
@Stable
sealed interface PageScroll {
    /** How far the page has scrolled from its top; saturates once a lazy page's first item is gone. */
    val offset: Int

    /** How much further the page can scroll down; saturates while a lazy page can scroll at all. */
    val remaining: Int

    suspend fun scrollBy(px: Float)

    suspend fun scrollToTop()
}

fun ScrollState.asPageScroll(): PageScroll = ColumnPageScroll(this)

fun LazyListState.asPageScroll(): PageScroll = LazyPageScroll(this)

private data class ColumnPageScroll(val state: ScrollState) : PageScroll {
    override val offset get() = state.value
    override val remaining get() = state.maxValue - state.value
    override suspend fun scrollBy(px: Float) {
        state.animateScrollBy(px)
    }
    override suspend fun scrollToTop() = state.animateScrollTo(0)
}

private data class LazyPageScroll(val state: LazyListState) : PageScroll {
    override val offset get() = if (state.firstVisibleItemIndex == 0) state.firstVisibleItemScrollOffset else Int.MAX_VALUE
    override val remaining get() = if (state.canScrollForward) Int.MAX_VALUE else 0
    override suspend fun scrollBy(px: Float) {
        state.animateScrollBy(px)
    }
    override suspend fun scrollToTop() = state.animateScrollToItem(0)
}
