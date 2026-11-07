package dev.kobbler.app.ui.detail

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import dev.kobbler.kobfocus.focusInert
import dev.kobbler.kobfocus.focusKeyGroup

@Composable
internal fun DetailActionRow(
    actions: List<RowAction>,
    accent: Color,
    wide: Boolean,
    modifier: Modifier = Modifier,
    exitDown: FocusRequester? = null,
) {
    // Left has nothing of the row's own to reach, and geometry found the cast or the foot of
    // the page instead. Cancelling hands the press to the scaffold, which opens the rail.
    val exits = Modifier.focusProperties {
        onExit = {
            when (requestedFocusDirection) {
                FocusDirection.Down -> exitDown?.requestFocus()
                FocusDirection.Left -> cancelFocusChange()
                else -> Unit
            }
        }
    }
    // Every control is composed, not just those that fit: entry focus is handed out from what
    // registered during composition, and a lead composed only once measured lost it to the
    // season strip below. What does not fit is left unplaced, but an unplaced node is still
    // in the semantics and focus trees, so it is taken out of both.
    var fits by remember { mutableIntStateOf(Int.MAX_VALUE) }
    val shown = fits.coerceAtMost(actions.size)
    val hidden: (Boolean) -> Modifier = { off -> (if (off) Modifier.clearAndSetSemantics { } else Modifier).focusInert(off) }
    val gap = if (wide) 12.dp else 8.dp
    Layout(
        modifier = modifier.fillMaxWidth().focusKeyGroup("actions").then(exits).focusGroup(),
        content = {
            actions.forEachIndexed { i, action ->
                key(action.key) {
                    Box(hidden(i >= shown), propagateMinConstraints = true) {
                        RowActionControl(action, lead = i == 0, accent, if (i == 0 && !wide) Modifier.fillMaxWidth() else Modifier)
                    }
                }
            }
            Box(hidden(shown >= actions.size)) {
                OverflowActions(accent = accent, labelled = true) { close -> actions.drop(shown).forEach { RowActionMenuEntry(it, accent, close) } }
            }
        },
    ) { measurables, constraints ->
        if (actions.isEmpty()) return@Layout layout(0, 0) { }
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val gapPx = gap.roundToPx()
        val lead = measurables.first()
        val rest = measurables.subList(1, actions.size).map { it.measure(loose) }
        val more = measurables.last().measure(loose)
        val widths = listOf(lead.maxIntrinsicWidth(loose.maxHeight)) + rest.map { it.width }
        fun span(count: Int) = widths.take(count).sum() + gapPx * (count - 1)
        val bounded = constraints.hasBoundedWidth
        val max = constraints.maxWidth
        val count = when {
            !bounded || span(actions.size) <= max -> actions.size
            else -> (actions.size - 1 downTo 1).firstOrNull { span(it) + gapPx + more.width <= max } ?: 1
        }
        fits = count
        val overflowing = count < actions.size
        val others = (1 until count).sumOf { widths[it] + gapPx } + if (overflowing) gapPx + more.width else 0
        val room = if (bounded) (max - others).coerceAtLeast(0) else widths[0]
        val leadPlaceable = lead.measure(
            if (!wide) loose.copy(minWidth = room, maxWidth = room) else loose.copy(maxWidth = room.coerceAtLeast(0)),
        )
        val row = listOf(leadPlaceable) + rest.take(count - 1) + listOfNotNull(more.takeIf { overflowing })
        val height = row.maxOf { it.height }
        val used = row.sumOf { it.width } + gapPx * (row.size - 1)
        val width = if (bounded && !wide) max else used.coerceIn(constraints.minWidth, constraints.maxWidth)
        layout(width, height) {
            var x = 0
            row.forEach {
                it.placeRelative(x, (height - it.height) / 2)
                x += it.width + gapPx
            }
        }
    }
}
