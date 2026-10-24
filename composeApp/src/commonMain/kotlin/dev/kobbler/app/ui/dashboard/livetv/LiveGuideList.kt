package dev.kobbler.app.ui.dashboard.livetv

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForwardIos
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.LiveChannel
import dev.kobbler.app.data.LiveGuideChannel
import dev.kobbler.app.data.LiveGuideEntry
import dev.kobbler.app.data.LiveOnNowItem
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.LocalTopChromeOverlap
import dev.kobbler.app.ui.PinnedHeader
import dev.kobbler.app.ui.components.EmptyState
import dev.kobbler.app.ui.scrollsUnderTopChrome
import dev.kobbler.kobfocus.focusKey
import kotlin.time.Instant

internal fun guideRowKey(channelId: String) = "lrow.$channelId"

/**
 * One row per channel: what is on at [at] (or now), and what follows. Picking a row plays it
 * when that programme is on air; otherwise it opens the channel, where its schedule is.
 */
@Composable
fun LiveGuideList(
    guide: List<LiveGuideChannel>,
    at: Instant?,
    clock: GuideClock,
    loading: Boolean,
    wide: Boolean,
    accent: Color,
    gutter: Dp,
    entryKey: String?,
    forYou: List<LiveOnNowItem>,
    recorded: Set<String>,
    chips: @Composable () -> Unit,
    onWatch: (LiveChannel) -> Unit,
    onOpenChannel: (LiveChannel) -> Unit,
    onRecord: (LiveGuideEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = KobappTheme.dimens
    val listState = rememberLazyListState()
    val focus = remember { GuideFocus() }
    CenterFocusedRow(listState, focus, topRow = 0)

    PinnedHeader(
        header = { Box(Modifier.padding(horizontal = gutter).padding(top = dimens.s2, bottom = dimens.s3)) { chips() } },
        modifier = modifier.fillMaxSize(),
    ) {
        if (guide.isEmpty()) {
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = accent, modifier = Modifier.size(28.dp))
                }
            } else {
                EmptyState(
                    icon = Icons.Outlined.Tv,
                    title = "No guide data",
                    subtitle = "The tuner has not synced an EPG for this time yet.",
                )
            }
            return@PinnedHeader
        }

        val showForYou = at == null && forYou.isNotEmpty()
        LazyColumn(
            state = listState,
            modifier = Modifier.scrollsUnderTopChrome().fillMaxSize(),
            contentPadding = PaddingValues(top = LocalTopChromeOverlap.current, bottom = dimens.s6),
            verticalArrangement = Arrangement.spacedBy(if (wide) dimens.s1 else 2.dp),
        ) {
            if (showForYou) {
                item(key = "foryou") {
                    LiveForYou(
                        items = forYou,
                        accent = accent,
                        gutter = gutter,
                        onWatch = onWatch,
                        modifier = Modifier.padding(bottom = dimens.s3),
                    )
                }
            }
            itemsIndexed(guide, key = { _, it -> it.channel.id }) { index, row ->
                val lazyIndex = if (showForYou) index + 1 else index
                GuideRow(
                    row = row,
                    at = at,
                    clock = clock,
                    wide = wide,
                    accent = accent,
                    recorded = recorded,
                    preferredEntry = guideRowKey(row.channel.id) == entryKey,
                    onWatch = onWatch,
                    onOpenChannel = onOpenChannel,
                    onRecord = onRecord,
                    modifier = Modifier
                        .padding(horizontal = gutter)
                        .onFocusChanged { if (it.hasFocus) focus.lazyIndex = lazyIndex },
                )
            }
        }
    }
}

@Composable
private fun GuideRow(
    row: LiveGuideChannel,
    at: Instant?,
    clock: GuideClock,
    wide: Boolean,
    accent: Color,
    recorded: Set<String>,
    preferredEntry: Boolean,
    onWatch: (LiveChannel) -> Unit,
    onOpenChannel: (LiveChannel) -> Unit,
    onRecord: (LiveGuideEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KobappTheme.colors
    // Derived so a clock tick only recomposes the rows whose lineup it actually changes.
    val lineup by remember(row.entries, clock, at) {
        derivedStateOf {
            val t = at ?: clock.now
            val running = row.entries.stillRunning(t)
            val current = running.firstOrNull()?.takeIf { it.isAiringAt(t) }
            current to running.filter { it != current }.take(2)
        }
    }
    val (current, following) = lineup
    val onAir by remember(current, clock) { derivedStateOf { current?.isAiringAt(clock.now) == true } }
    val channel = row.channel
    val recordable = current != null && !onAir
    val isRecorded = current?.let { it.id in recorded || it.recordingId != null } == true

    ChannelGuideRow(
        number = channel.lcn ?: "",
        name = channel.name,
        logo = { size -> ChannelLogo(channel, accent, size) },
        badges = { if (channel.hd) LiveBadge("HD", accent) },
        current = current?.slot(),
        following = following.map { it.slot() },
        showProgress = at == null,
        clock = clock,
        wide = wide,
        accent = accent,
        groupKey = "gch.${channel.id}",
        rowKey = guideRowKey(channel.id),
        preferredEntry = preferredEntry,
        emptyLabel = "No listing",
        onClick = { if (onAir) onWatch(channel) else onOpenChannel(channel) },
        modifier = modifier,
    ) {
        when {
            recordable && isRecorded -> RowIconButton(
                icon = Icons.Filled.FiberManualRecord,
                label = "Recording ${current?.title}",
                focusKey = "record",
                wide = wide,
                onClick = null,
                tint = colors.err,
                iconSize = 16.dp,
                border = colors.err,
            )
            recordable -> RowIconButton(
                icon = Icons.Filled.FiberManualRecord,
                label = "Record ${current?.title}",
                focusKey = "record",
                wide = wide,
                onClick = { current?.let(onRecord) },
                tint = colors.err,
                iconSize = 12.dp,
            )
            else -> RowIconButton(
                icon = Icons.AutoMirrored.Outlined.ArrowForwardIos,
                label = "${channel.name} schedule",
                focusKey = "schedule",
                wide = wide,
                onClick = { onOpenChannel(channel) },
            )
        }
    }
}

private fun LiveGuideEntry.slot() = GuideSlot(title, parseLiveInstant(startTime), parseLiveInstant(endTime))
