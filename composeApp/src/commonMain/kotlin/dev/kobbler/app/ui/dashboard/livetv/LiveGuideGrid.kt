package dev.kobbler.app.ui.dashboard.livetv

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.kobbler.app.data.LiveChannel
import dev.kobbler.app.data.LiveGuideChannel
import dev.kobbler.app.data.LiveGuideEntry
import dev.kobbler.app.data.LiveRecording
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.EmptyState
import dev.kobbler.app.ui.components.KobButton
import dev.kobbler.app.ui.components.KobButtonSize
import dev.kobbler.app.ui.components.KobButtonStyle
import dev.kobbler.app.ui.components.rememberTvFocusState
import dev.kobbler.app.ui.components.tvClickable
import dev.kobbler.app.ui.components.tvFocusGlow
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlin.time.Instant

private val ChannelColumnWidth = 250.dp
private val ChannelColumnWidthCompact = 165.dp
private val GuideRowHeight = 76.dp
private val GuideRowHeightDense = 62.dp

// How wide one minute of programme is. This, and not the length of the loaded window, is
// what decides whether a cell is readable: fitting the whole window into the track meant
// the more guide the server returned the smaller every programme got, and a half-hour
// show came out as a box with a clipped title. Fixed here and scrolled instead, so a
// half-hour is always ~3.5cm on a TV no matter how much guide is loaded behind it.
private val GuideMinuteWidth = 7.dp
private val GuideMinuteWidthDense = 5.dp

// What the cell under the cursor grows to when the schedule gives it less.
//
// One shared scale cannot be right for both ends of a lineup: a channel that fills the
// gaps with five-minute shorts draws them 35dp wide, which is a box with no room for a
// title, and stretching the axis until they fit would spread everyone else's half-hours
// over three screens. Every ten-foot guide worth copying answers this the same way —
// the axis stays put and the programme being looked at is the one that gets the room.
private val FocusedCellMinWidth = 168.dp
private val FocusedCellMinWidthDense = 138.dp

// A 1080p TV reports 540dp of height. The rich preview alone is half of that, so the
// grid it sits above measured to zero and the page rendered as a header with nothing
// under it. Anything below this budget gets the dense layout instead.
private val DenseGuideMaxHeight = 620.dp
private val PreviewHeight = 270.dp
private val PreviewHeightDense = 132.dp
private val PreviewWidth = 480.dp
private val PreviewWidthDense = 234.dp

// Fast enough to feel like the cursor landing, slow enough to read as a zoom.
private const val FocusZoomMs = 140
private const val ArtworkSettleMs = 150L

/**
 * The 10-foot guide: a preview pane describing whatever the cursor is on, a facet strip,
 * and a time-axis grid.
 *
 * The time axis scrolls horizontally and every row shares one [ScrollState] with the
 * ruler, so the axis stays true across the grid however far along it the viewer is. The
 * channel column is outside that scroller and stays pinned. [onShiftWindow] loads a
 * different window when the viewer reaches the end of this one.
 */
@Composable
fun LiveGuideGrid(
    guide: List<LiveGuideChannel>,
    windowStart: Instant,
    windowMinutes: Int,
    clock: GuideClock,
    selection: LiveGuideSelectionHolder,
    activeRecordings: List<LiveRecording>,
    loading: Boolean,
    accent: Color,
    canWant: Boolean,
    gutter: Dp,
    modifier: Modifier = Modifier,
    facetStrip: @Composable () -> Unit,
    onSelect: (LiveGuideSelection) -> Unit,
    onWatch: (LiveChannel) -> Unit,
    onRecord: (LiveGuideEntry) -> Unit,
    onAddToWanted: (LiveGuideSelection) -> Unit,
    onShiftWindow: (Int) -> Unit,
) {
    val colors = KobappTheme.colors
    val dimens = KobappTheme.dimens
    val listState = rememberLazyListState()
    val trackScroll = rememberScrollState()
    val focus = remember { GuideFocus() }
    val nav = remember { GuideNav() }
    val density = LocalDensity.current
    CenterFocusedRow(listState, focus)

    BoxWithConstraints(modifier.fillMaxSize()) {
        val dense = maxHeight < DenseGuideMaxHeight
        val rowHeight = if (dense) GuideRowHeightDense else GuideRowHeight
        val vGap = if (dense) dimens.s2 else dimens.s3

        Column(Modifier.fillMaxSize()) {
            LiveGuidePreview(
                selection = selection,
                activeRecordings = activeRecordings,
                accent = accent,
                canWant = canWant,
                dense = dense,
                onWatch = onWatch,
                onRecord = onRecord,
                onAddToWanted = onAddToWanted,
                modifier = Modifier.padding(horizontal = gutter)
                    .padding(top = if (dense) dimens.s2 else dimens.s4, bottom = vGap),
            )

            // A ten-foot screen has 540dp to spend and every one of them is a channel the
            // viewer cannot see. The network filter is a page of chrome that answers a
            // question ("only show me SBS") a remote asks about once a month, so it only
            // appears where there is room for it; the axis controls move into the channel
            // column's header, which is empty space the ruler was already paying for.
            if (!dense) {
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = gutter).padding(bottom = vGap)
                        .clipToBounds(),
                ) { facetStrip() }
            }

            if (guide.isEmpty()) {
                if (loading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = accent, modifier = Modifier.size(28.dp))
                    }
                } else {
                    EmptyState(
                        icon = Icons.Outlined.Tv,
                        title = "No guide data",
                        subtitle = "The tuner has not synced an EPG for this window yet.",
                    )
                }
                return@Column
            }

            val compact = gutter <= 20.dp || dense
            val labelWidth = if (compact) ChannelColumnWidthCompact else ChannelColumnWidth
            val minuteWidth = if (dense) GuideMinuteWidthDense else GuideMinuteWidth
            val trackWidth = minuteWidth * windowMinutes

            // The window is fetched once and the track is a fixed minutes-per-dp, so an
            // evening spent on the page walks what is on air off the right of the screen.
            // Only ever forwards: a viewer who has scrolled ahead to read the schedule is
            // reading it, and dragging them back to now would be the page taking the
            // remote off them. The clock is read in the effect rather than the body so a
            // tick does not recompose the grid around it.
            LaunchedEffect(windowStart, minuteWidth) {
                // A freshly loaded window starts at its own beginning; carrying the old
                // offset over would drop the viewer into the middle of hours they have
                // not looked at.
                trackScroll.scrollTo(0)
                snapshotFlow { clock.now }.collect { now ->
                    val minutes = ((now - windowStart).inWholeSeconds / 60f)
                    if (minutes <= 0f) return@collect
                    val edge = with(density) { (minuteWidth * minutes).roundToPx() }
                    if (trackScroll.value < edge) trackScroll.scrollTo(edge)
                }
            }

            TimeRuler(
                labelWidth = labelWidth,
                windowStart = windowStart,
                windowMinutes = windowMinutes,
                minuteWidth = minuteWidth,
                scroll = trackScroll,
                gutter = gutter,
                dense = dense,
                onShiftWindow = onShiftWindow,
                accent = accent,
            )

            // weight, not fillMaxSize: a Column hands a non-weighted child the space its
            // siblings left, which on a 540dp TV was nothing at all.
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(start = gutter, bottom = if (dense) dimens.s3 else dimens.s6),
            ) {
                itemsIndexed(guide, key = { _, it -> it.channel.id }) { index, row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(rowHeight)
                            .focusKeyGroup("gch.${row.channel.id}")
                            .onFocusChanged {
                                if (it.hasFocus) focus.lazyIndex = index
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GuideChannelLabel(row.channel, accent, labelWidth, dense)
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .horizontalScroll(trackScroll),
                        ) {
                            // A scroller clips along its own axis, and a focus ring is
                            // drawn outside the cell it belongs to — so a cell scrolled
                            // flush against either end would lose that edge of its ring.
                            // The ruler carries the same inset, which keeps the two in step.
                            Box(
                                Modifier
                                    .padding(horizontal = dimens.focusBleed)
                                    .width(trackWidth)
                                    .fillMaxHeight(),
                            ) {
                                GuideCells(
                                    row = row,
                                    rowIndex = index,
                                    guide = guide,
                                    nav = nav,
                                    windowStart = windowStart,
                                    windowMinutes = windowMinutes,
                                    minuteWidth = minuteWidth,
                                    clock = clock,
                                    accent = accent,
                                    dense = dense,
                                    onSelect = onSelect,
                                    onWatch = onWatch,
                                )
                                NowMarker(windowStart, windowMinutes, minuteWidth, clock, accent)
                            }
                        }
                    }
                }
            }
        }
    }
}

private class GuideSpan(val entry: LiveGuideEntry, val startMs: Long, val endMs: Long)

@Composable
private fun BoxScope.GuideCells(
    row: LiveGuideChannel,
    rowIndex: Int,
    guide: List<LiveGuideChannel>,
    nav: GuideNav,
    windowStart: Instant,
    windowMinutes: Int,
    minuteWidth: Dp,
    clock: GuideClock,
    accent: Color,
    dense: Boolean,
    onSelect: (LiveGuideSelection) -> Unit,
    onWatch: (LiveChannel) -> Unit,
) {
    val startMs = windowStart.toEpochMilliseconds()
    val endMs = startMs + windowMinutes * 60_000L
    val spans = remember(row.entries) {
        row.entries.mapNotNull { e ->
            val s = parseLiveInstant(e.startTime)?.toEpochMilliseconds() ?: return@mapNotNull null
            val en = parseLiveInstant(e.endTime)?.toEpochMilliseconds() ?: return@mapNotNull null
            GuideSpan(e, s, en)
        }
    }
    // Everything the track has scrolled past. Drawn, they are cells the cursor can land
    // on and the one press the page is for does nothing from — and the guide would be
    // holding open a lane for hours nobody can watch. Derived so a clock tick only
    // recomposes the row when a programme actually ends.
    val visible by remember(spans, startMs, endMs, clock) {
        derivedStateOf {
            val liveMs = maxOf(startMs, clock.now.toEpochMilliseconds())
            spans.filter { it.endMs > liveMs && it.startMs < endMs }
        }
    }
    if (visible.isEmpty()) {
        Text(
            "No guide data",
            style = KobappTheme.type.caption,
            color = KobappTheme.colors.textXlo,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 14.dp),
        )
        return
    }
    visible.forEachIndexed { i, span ->
        val entry = span.entry
        GuideCell(
            span = span,
            channelId = row.channel.id,
            rowIndex = rowIndex,
            guide = guide,
            nav = nav,
            windowStart = windowStart,
            windowEndMs = endMs,
            clipToLive = i == 0,
            minuteWidth = minuteWidth,
            clock = clock,
            accent = accent,
            dense = dense,
            onSelect = { onSelect(LiveGuideSelection(row.channel, entry)) },
            // The one press the whole page is for, and only for what is on air. Moving
            // onto a cell has already selected it, so answering the press with another
            // selection is a control that does nothing.
            onWatch = { onWatch(row.channel) },
        )
    }
}

@Composable
private fun GuideCell(
    span: GuideSpan,
    channelId: String,
    rowIndex: Int,
    guide: List<LiveGuideChannel>,
    nav: GuideNav,
    windowStart: Instant,
    windowEndMs: Long,
    clipToLive: Boolean,
    minuteWidth: Dp,
    clock: GuideClock,
    accent: Color,
    dense: Boolean,
    onSelect: () -> Unit,
    onWatch: () -> Unit,
) {
    val entry = span.entry
    val colors = KobappTheme.colors
    val focusState = rememberTvFocusState()
    val requester = remember { FocusRequester() }
    DisposableEffect(nav, channelId, entry.id, requester) {
        nav.register(channelId, entry.id, requester)
        onDispose { nav.unregister(channelId, entry.id, requester) }
    }
    val recording = entry.recordingId != null
    val onAir by remember(span, clock) {
        derivedStateOf { clock.now.toEpochMilliseconds().let { it >= span.startMs && it < span.endMs } }
    }
    val windowStartMs = windowStart.toEpochMilliseconds()
    // Only the row's first cell is cut by the live edge, so only it follows every tick.
    val shownStartMs = if (clipToLive) {
        maxOf(span.startMs, windowStartMs, clock.now.toEpochMilliseconds())
    } else {
        maxOf(span.startMs, windowStartMs)
    }
    val widthDp = minuteWidth * ((span.endMs.coerceAtMost(windowEndMs) - shownStartMs) / 60_000f)
    val meta = remember(entry) {
        listOfNotNull(
            parseLiveInstant(entry.startTime)?.localHhMm(),
            entry.episodeNumber,
            entry.genres.firstOrNull(),
        ).joinToString(" · ")
    }
    val border = when {
        recording -> colors.err
        onAir -> accent
        else -> colors.lineSoft
    }
    val minWidth = if (dense) FocusedCellMinWidthDense else FocusedCellMinWidth
    val width = animateDpAsState(
        targetValue = if (focusState.isFocused) widthDp.coerceAtLeast(minWidth) else widthDp,
        animationSpec = tween(FocusZoomMs),
    )
    val metaThreshold = if (dense) 128.dp else 96.dp
    val showMeta by remember(width, metaThreshold) { derivedStateOf { width.value > metaThreshold } }
    val showRecordingIcon by remember(width) { derivedStateOf { width.value > 72.dp } }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .offset(x = minuteWidth * ((shownStartMs - windowStartMs) / 60_000f))
            // Over the neighbour it has grown into, not under it.
            .zIndex(if (focusState.isFocused) 1f else 0f)
            .layout { measurable, constraints ->
                val w = constraints.constrainWidth(width.value.roundToPx())
                val placeable = measurable.measure(constraints.copy(minWidth = w, maxWidth = w))
                layout(w, placeable.height) { placeable.place(0, 0) }
            }
            .fillMaxHeight()
            .padding(vertical = if (dense) 3.dp else 5.dp, horizontal = 2.dp)
            .focusKey("e.${entry.id}", focusRequester = requester)
            .focusProperties {
                down = nav.target(guide, rowIndex, 1)
                up = nav.target(guide, rowIndex, -1)
            }
            .tvFocusGlow(focusState, cornerRadiusDp = 10f, scale = 1.02f)
            .clip(RoundedCornerShape(10.dp))
            .background(if (onAir) lerp(colors.elev1, accent, 0.14f) else colors.elev1)
            .border(1.dp, border, RoundedCornerShape(10.dp))
            .onFocusChanged {
                if (it.isFocused) {
                    nav.onFocused(entry, maxOf(windowStart, clock.now))
                    onSelect()
                }
            }
            .tvClickable(focusState) {
                onSelect()
                if (onAir) onWatch()
            }
            .padding(horizontal = if (dense) 9.dp else 12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                entry.title,
                style = KobappTheme.type.bodySm.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textHi,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta.isNotEmpty() && showMeta) {
                Text(
                    meta,
                    style = KobappTheme.type.caption,
                    color = colors.textMd,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (recording && showRecordingIcon) {
            Icon(
                Icons.Outlined.FiberManualRecord,
                contentDescription = "Recording",
                tint = colors.err,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

@Composable
private fun BoxScope.NowMarker(
    windowStart: Instant,
    windowMinutes: Int,
    minuteWidth: Dp,
    clock: GuideClock,
    accent: Color,
) {
    val startMs = windowStart.toEpochMilliseconds()
    val spanMs = windowMinutes * 60_000L
    val inWindow by remember(startMs, spanMs, clock) {
        derivedStateOf { clock.now.toEpochMilliseconds().let { it in startMs..(startMs + spanMs) } }
    }
    if (!inWindow) return
    Box(
        Modifier
            .offset {
                val minutes = (clock.now.toEpochMilliseconds() - startMs) / 60_000f
                IntOffset((minuteWidth * minutes).roundToPx(), 0)
            }
            .width(3.dp)
            .fillMaxHeight()
            .background(accent),
    )
}

@Composable
private fun TimeRuler(
    labelWidth: Dp,
    windowStart: Instant,
    windowMinutes: Int,
    minuteWidth: Dp,
    scroll: ScrollState,
    gutter: Dp,
    dense: Boolean,
    accent: Color,
    onShiftWindow: (Int) -> Unit,
) {
    val colors = KobappTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Start only: the axis is a scroller, so an end gutter buys nothing but a
            // half-drawn clock face at the edge of the screen.
            .padding(start = gutter)
            .height(if (dense) 40.dp else 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The column header says "channel" by having channels under it. What it can say
        // instead is which part of the schedule is on screen, and how to move it.
        GuideWindowStepper(
            windowStart = windowStart,
            windowMinutes = windowMinutes,
            accent = accent,
            compact = dense,
            onShift = onShiftWindow,
            stepMinutes = windowMinutes,
            modifier = Modifier.width(labelWidth).padding(end = if (dense) 8.dp else 12.dp),
        )
        // The rows own the gesture; the ruler only follows them. Left draggable it would
        // be a second way to move the axis that the rows would then have to chase.
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .horizontalScroll(scroll, enabled = false)
                .padding(horizontal = KobappTheme.dimens.focusBleed),
        ) {
            // Placed at their own times rather than laid out as equal boxes from the left
            // edge: the window opens at now, so its first half hour is a part-slot and a
            // row of full ones would put every clock face at the wrong offset.
            Box(Modifier.width(minuteWidth * windowMinutes).fillMaxHeight()) {
                guideRulerTicks(windowStart, windowMinutes).forEach { at ->
                    Text(
                        at.localHhMm(),
                        style = KobappTheme.type.caption.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.textMd,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .offset(x = minuteWidth * ((at - windowStart).inWholeMinutes.toFloat()))
                            .padding(start = if (dense) 6.dp else 8.dp),
                    )
                }
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
}

@Composable
private fun GuideChannelLabel(channel: LiveChannel, accent: Color, width: Dp, dense: Boolean) {
    val colors = KobappTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (dense) 8.dp else 12.dp),
        modifier = Modifier.width(width).fillMaxHeight().padding(end = if (dense) 10.dp else 16.dp),
    ) {
        // No channel number: the logo and the name already name the channel, and nothing
        // here is dialled by number. The column it cost is track the programmes get back.
        if (channel.logoUrl != null) {
            ChannelLogo(channel, accent, if (dense) 26.dp else 34.dp)
        } else {
            Box(Modifier.width(4.dp).height(if (dense) 26.dp else 36.dp).clip(RoundedCornerShape(2.dp)).background(accent))
        }
        Text(
            channel.name,
            style = KobappTheme.type.bodySm.copy(fontWeight = FontWeight.SemiBold),
            color = colors.textHi,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (channel.hd && !dense) LiveBadge("HD", accent)
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun LiveGuidePreview(
    selection: LiveGuideSelectionHolder,
    activeRecordings: List<LiveRecording>,
    accent: Color,
    canWant: Boolean,
    dense: Boolean,
    onWatch: (LiveChannel) -> Unit,
    onRecord: (LiveGuideEntry) -> Unit,
    onAddToWanted: (LiveGuideSelection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KobappTheme.colors
    val dimens = KobappTheme.dimens
    val selected = selection.value
    // Trails the cursor: every D-pad step through the grid would otherwise start a fetch
    // and decode for a still nobody stops on.
    val artEntry by produceState(selected?.entry, selection) {
        snapshotFlow { selection.value?.entry }.debounce(ArtworkSettleMs).collect { value = it }
    }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (dense) dimens.s4 else dimens.s6)) {
        Column(
            Modifier.width(if (dense) PreviewWidthDense else PreviewWidth),
            verticalArrangement = Arrangement.spacedBy(dimens.s3),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(if (dense) PreviewHeightDense else PreviewHeight)
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.elev1)
                    .border(1.dp, colors.line, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                GuideArtwork(artEntry, Color.Transparent, Modifier.matchParentSize(), scrim = 0.55f)
                val previewChannel = selected?.channel
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(dimens.s2)) {
                    if (previewChannel != null) {
                        ChannelLogo(previewChannel, accent, if (dense) 38.dp else 60.dp)
                        if (!dense) Text(previewChannel.name, style = KobappTheme.type.bodySm, color = colors.textHi)
                    } else {
                        Icon(Icons.Outlined.Tv, null, tint = colors.textXlo, modifier = Modifier.size(48.dp))
                    }
                }
                val channel = selected?.channel
                if (channel != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(12.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.bg.copy(alpha = 0.7f))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        RecordingDot(accent, 7.dp)
                        Text(channel.name, style = KobappTheme.type.caption.copy(fontWeight = FontWeight.SemiBold), color = colors.textHi)
                    }
                }
            }
            val inProgress = activeRecordings.firstOrNull { it.isInProgress }
            if (inProgress != null && !dense) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RecordingDot(colors.err)
                    Text(
                        buildString {
                            append("Recording ")
                            append(inProgress.title)
                            inProgress.channelName?.let { append(" on $it") }
                            inProgress.tunerIndex?.let { append(" · tuner ${it + 1}") }
                        },
                        style = KobappTheme.type.bodySm,
                        color = colors.textMd,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Column(
            Modifier.weight(1f).height(if (dense) PreviewHeightDense else PreviewHeight),
            verticalArrangement = Arrangement.spacedBy(if (dense) dimens.s1 else dimens.s3),
        ) {
            val entry = selected?.entry
            if (selected == null || entry == null) {
                Text(
                    "Pick a programme",
                    style = if (dense) KobappTheme.type.h2 else KobappTheme.type.h1,
                    color = colors.textLo,
                )
                Text(
                    "Move through the grid below and this pane follows.",
                    style = if (dense) KobappTheme.type.bodySm else KobappTheme.type.body,
                    color = colors.textLo,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val genre = entry.genres.firstOrNull()
                if (genre != null) {
                    Text(genre.uppercase(), style = KobappTheme.type.labelSm, color = accent)
                    Box(Modifier.size(4.dp).clip(RoundedCornerShape(999.dp)).background(colors.textXlo))
                }
                Text(
                    listOfNotNull(selected.channel.name, selected.channel.lcn).joinToString(" · "),
                    style = KobappTheme.type.caption,
                    color = colors.textMd,
                )
            }
            Text(
                entry.title,
                style = if (dense) KobappTheme.type.h2 else KobappTheme.type.h1,
                color = colors.textHi,
                maxLines = if (dense) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (dense) 12.dp else 18.dp)) {
                Text(hhmmRange(entry.startTime, entry.endTime), style = KobappTheme.type.bodySm, color = colors.textMd)
                val dur = minutesLabel(entry.durationSeconds)
                if (dur.isNotEmpty()) Text(dur, style = KobappTheme.type.bodySm, color = colors.textMd)
                if (entry.genres.isNotEmpty() && !dense) {
                    Text(entry.genres.joinToString(" · "), style = KobappTheme.type.bodySm, color = colors.textMd)
                }
                val codecs = listOfNotNull(
                    if (selected.channel.hd) "1080i" else selected.channel.videoCodec,
                    selected.channel.audioCodec?.uppercase(),
                ).joinToString(" · ")
                if (codecs.isNotEmpty() && !dense) LiveBadge(codecs, accent)
                if (entry.repeatCount > 0 && !dense) {
                    Text(
                        "${entry.repeatCount} other airings",
                        style = KobappTheme.type.caption,
                        color = colors.textLo,
                    )
                }
            }
            if (!dense) {
                val synopsis = entry.synopsis
                Text(
                    synopsis ?: "No synopsis in the guide feed for this programme.",
                    style = KobappTheme.type.bodySm,
                    color = colors.textMd,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.weight(1f))
            val btnSize = if (dense) KobButtonSize.Default else KobButtonSize.Prominent
            val iconSize = if (dense) 16.dp else 20.dp
            Row(
                horizontalArrangement = Arrangement.spacedBy(if (dense) dimens.s2 else dimens.s3),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.focusKeyGroup("preview"),
            ) {
                KobButton(
                    text = "Watch",
                    onClick = { onWatch(selected.channel) },
                    style = KobButtonStyle.Accent,
                    size = btnSize,
                    accent = accent,
                    cornerRadiusDp = 999f,
                    leading = { Icon(Icons.Outlined.PlayArrow, null, modifier = Modifier.size(iconSize)) },
                    // Where the remote starts: the pane above describes the channel
                    // last watched, so the first press of the session is "put that on".
                    modifier = Modifier.focusKey("watch", preferredEntry = true),
                )
                val recorded = entry.recordingId != null
                KobButton(
                    text = if (recorded) "Recording" else "Record",
                    onClick = { if (!recorded) onRecord(entry) },
                    style = KobButtonStyle.Default,
                    size = btnSize,
                    enabled = !recorded,
                    cornerRadiusDp = 999f,
                    leading = { Icon(Icons.Outlined.FiberManualRecord, null, tint = colors.err, modifier = Modifier.size(iconSize)) },
                    modifier = Modifier.focusKey("record", entryEligible = false),
                )
                if (canWant) {
                    KobButton(
                        text = "Want",
                        onClick = { onAddToWanted(selected) },
                        style = KobButtonStyle.Default,
                        size = btnSize,
                        cornerRadiusDp = 999f,
                        leading = { Icon(Icons.Outlined.BookmarkAdd, null, modifier = Modifier.size(iconSize)) },
                        modifier = Modifier.focusKey("wanted", entryEligible = false),
                    )
                }
            }
        }
    }
}
