package dev.kobbler.app.ui.detail.tvshow

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.Season
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.FocusEdgeSlack
import dev.kobbler.app.ui.components.bleedHorizontally
import dev.kobbler.app.ui.components.focusEdgeScrollDistance
import dev.kobbler.app.ui.components.rememberTvFocusState
import dev.kobbler.app.ui.components.tvClickable
import dev.kobbler.app.ui.components.tvFocusGlow
import dev.kobbler.app.ui.detail.OverflowActions
import dev.kobbler.app.ui.detail.OverflowMenu
import dev.kobbler.app.ui.detail.gutterFor
import dev.kobbler.kobfocus.focusKey
import kotlinx.coroutines.delay

private const val SeasonFocusSelectDelayMs = 180L

private val MenuFade = 24.dp
private val LabelToRule = 6.dp
private val ActiveRule = 2.dp

// A phone's touch target is taller than the strip; counted, it stood the tabs off their rule.
private fun Modifier.centredWithoutHeight(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minHeight = 0))
    layout(placeable.width, 0) { placeable.place(0, -placeable.height / 2) }
}

/**
 * The strip scrolls out to both edges of the screen. Where a season menu sits at its end,
 * tabs run under the menu and fade out ahead of it, and the selected one is kept clear of
 * it.
 */
@Composable
fun SeasonTabs(
    seasons: List<Season>,
    selectedIdx: Int,
    accent: Color,
    formFactor: FormFactor,
    onSelected: (Int) -> Unit,
    entryFocus: FocusRequester? = null,
    menu: (@Composable ColumnScope.(close: () -> Unit) -> Unit)? = null,
) {
    val colors = KobappTheme.colors
    val gutter = gutterFor(formFactor)
    val scrollState = rememberScrollState()
    // Focus alone switches season on a remote, so browsing costs one keypress instead
    // of two. Driven by focus rather than hover so a mouse pointer never triggers it.
    val focusSelects = formFactor == FormFactor.TV
    val menuButton = !focusSelects && menu != null
    var focusedIdx by remember { mutableStateOf(-1) }
    var menuIdx by remember { mutableStateOf(-1) }
    var menuWidthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val menuWidth = with(density) { menuWidthPx.toDp() }
    var selectedSpan by remember { mutableStateOf<IntRange?>(null) }
    // A remote reveals the tab it focuses; a click or a page opened mid-show selects one
    // that may sit past the end of the strip, or under the menu.
    if (!focusSelects) {
        LaunchedEffect(selectedSpan, menuWidthPx, scrollState.viewportSize) {
            val span = selectedSpan ?: return@LaunchedEffect
            val gutterPx = with(density) { gutter.roundToPx() }
            // The span is measured inside the strip's padding; the scroll runs over it.
            val start = span.first
            val end = span.last + gutterPx * 2 + menuWidthPx
            val distance = focusEdgeScrollDistance(
                offset = (start - scrollState.value).toFloat(),
                size = (end - start).toFloat(),
                containerSize = scrollState.viewportSize.toFloat(),
                margin = 0f,
                hold = false,
            )
            if (distance != 0f) scrollState.animateScrollTo(scrollState.value + distance.toInt())
        }
    }
    if (focusSelects) {
        // Keyed on the focused tab, so scrubbing past a season cancels its pending
        // commit instead of tearing the episode list down once per keypress.
        LaunchedEffect(focusedIdx) {
            if (focusedIdx < 0) return@LaunchedEffect
            delay(SeasonFocusSelectDelayMs)
            onSelected(focusedIdx)
        }
    }
    Box(Modifier.fillMaxWidth()) {
        // Behind the row, not after it: the focus ring bleeds a few dp past the tab's
        // bounds and a following sibling painted this rule straight across it.
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .bleedHorizontally(gutter)
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.lineSoft),
        )
        // Its own slack, and its own spec: the page above installs a vertical bring-into-view
        // rule that would otherwise be applied to this strip's horizontal axis.
        FocusEdgeSlack {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .bleedHorizontally(gutter)
                    .then(if (menuButton) Modifier.fadeOutBefore(gutter + menuWidth) else Modifier)
                    .horizontalScroll(scrollState)
                    .padding(start = gutter, end = gutter + menuWidth),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                seasons.forEachIndexed { idx, season ->
                    val active = idx == selectedIdx
                    val focusState = rememberTvFocusState()
                    val anchor = remember { FocusRequester() }
                    Box(if (active) Modifier.onPlaced { selectedSpan = it.positionInParent().x.toInt().let { x -> x..x + it.size.width } } else Modifier) {
                        Column(
                            modifier = Modifier
                                .focusKey("season.${season.number}")
                                .focusRequester(anchor)
                                // Entry lands on the selected season rather than whichever tab is
                                // geometrically nearest, which on a remote is always season one.
                                .then(if (active && entryFocus != null) Modifier.focusRequester(entryFocus) else Modifier)
                                .tvFocusGlow(focusState, cornerRadiusDp = 8f, scale = 1.02f)
                                .clip(KobappTheme.shapes.small)
                                .pointerHoverIcon(PointerIcon.Hand)
                                .onFocusChanged { if (it.isFocused) focusedIdx = idx }
                                .tvClickable(focusState) {
                                    onSelected(idx)
                                    // Focus already selects on a remote, which leaves OK free to
                                    // open the season's own menu without stepping off the tab.
                                    if (focusSelects && menu != null) menuIdx = idx
                                }
                                // No bottom inset: the active rule sits on the strip's own line, and
                                // the space above the label matches the 6 + 2 below it.
                                .padding(start = 16.dp, end = 16.dp, top = 8.dp),
                        ) {
                            // Weight is fixed across states on purpose: selection follows focus on
                            // TV, and a bolder active label re-measures the row and shunts every
                            // tab after it sideways on each keypress. Colour and rule carry it.
                            Text(
                                "Season ${season.number}",
                                style = KobappTheme.type.bodySm.copy(fontWeight = FontWeight.Medium),
                                color = if (active) colors.textHi else colors.textLo,
                            )
                            Spacer(Modifier.height(LabelToRule))
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(ActiveRule)
                                    .background(if (active) accent else Color.Transparent),
                            )
                        }
                        if (menu != null && menuIdx == idx) {
                            OverflowMenu(expanded = true, anchor = anchor, onClose = { menuIdx = -1 }, content = menu)
                        }
                    }
                }
            }
        }
        // A pointer selects by clicking, so the menu needs a control of its own there.
        if (menuButton) {
            val labelCentre = ActiveRule + LabelToRule + with(density) { KobappTheme.type.bodySm.lineHeight.toDp() } / 2
            OverflowActions(
                accent = accent,
                label = "Season ${seasons.getOrNull(selectedIdx)?.number} options",
                focusKeyName = "season.more",
                quiet = true,
                content = menu,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = labelCentre)
                    .centredWithoutHeight()
                    .onSizeChanged { menuWidthPx = it.width },
            )
        }
    }
}

// Faded in a layer of its own rather than the whole strip's: an offscreen graphics layer
// clips to the strip's height, and the focus ring is drawn above and below the tabs.
private fun Modifier.fadeOutBefore(end: Dp): Modifier = drawWithContent {
    val clear = size.width - end.toPx()
    val fadeStart = clear - MenuFade.toPx()
    val top = -size.height
    val bottom = size.height * 2
    clipRect(left = -size.width, top = top, right = fadeStart, bottom = bottom) {
        this@drawWithContent.drawContent()
    }
    val band = Rect(fadeStart, top, clear, bottom)
    drawIntoCanvas { canvas ->
        canvas.saveLayer(band, Paint())
        clipRect(band.left, band.top, band.right, band.bottom) { this@drawWithContent.drawContent() }
        drawRect(
            Brush.horizontalGradient(0f to Color.Black, 1f to Color.Transparent, startX = band.left, endX = band.right),
            topLeft = band.topLeft,
            size = band.size,
            blendMode = BlendMode.DstIn,
        )
        canvas.restore()
    }
}
