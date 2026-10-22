package dev.kobbler.app.ui.dashboard.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.LiveChannel
import dev.kobbler.app.data.LiveGuideChannel
import dev.kobbler.app.data.LiveGuideEntry
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.EmptyState
import dev.kobbler.app.ui.components.rememberTvFocusState
import dev.kobbler.app.ui.components.tvClickable
import dev.kobbler.app.ui.components.tvFocusGlow
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup
import kotlin.time.Instant

private val CardWidth = 158.dp
private val CardHeight = 78.dp

/** Fixed so every row's logo starts at the same x, whether the number is 1 or 101. */
private val LcnLaneWidth = 26.dp

@Composable
fun LivePhoneGuide(
    guide: List<LiveGuideChannel>,
    watching: LiveChannel?,
    loading: Boolean,
    accent: Color,
    gutter: Dp,
    entryKey: String?,
    windowStart: Instant,
    windowMinutes: Int,
    clock: GuideClock,
    modifier: Modifier = Modifier,
    facetStrip: @Composable () -> Unit,
    onSelect: (LiveGuideSelection) -> Unit,
    onWatch: (LiveChannel) -> Unit,
    onShiftWindow: (Int) -> Unit,
) {
    val colors = KobappTheme.colors
    val dimens = KobappTheme.dimens
    val listState = rememberLazyListState()
    val focus = remember { GuideFocus() }
    CenterFocusedRow(listState, focus, topRow = 0)

    Column(modifier.fillMaxSize()) {
        Box(Modifier.padding(horizontal = gutter, vertical = dimens.s3)) { facetStrip() }

        GuideWindowStepper(
            windowStart = windowStart,
            windowMinutes = windowMinutes,
            accent = accent,
            compact = true,
            onShift = onShiftWindow,
            modifier = Modifier.padding(horizontal = gutter).padding(bottom = dimens.s3),
        )

        if (watching != null) {
            WatchingPill(watching, accent, Modifier.padding(horizontal = gutter).padding(bottom = dimens.s3)) {
                onWatch(watching)
            }
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

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = dimens.s6),
            verticalArrangement = Arrangement.spacedBy(dimens.s4),
        ) {
            itemsIndexed(guide, key = { _, it -> it.channel.id }) { index, row ->
                Column(
                    verticalArrangement = Arrangement.spacedBy(dimens.s2),
                    modifier = Modifier
                        .focusKeyGroup("pch.${row.channel.id}")
                        .onFocusChanged {
                            if (it.hasFocus) focus.lazyIndex = index
                        },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                        modifier = Modifier.padding(horizontal = gutter),
                    ) {
                        Text(
                            row.channel.lcn ?: "",
                            style = KobappTheme.type.caption.copy(fontWeight = FontWeight.Bold),
                            color = colors.textMd,
                            maxLines = 1,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(LcnLaneWidth),
                        )
                        if (row.channel.logoUrl != null) {
                            ChannelLogo(row.channel, accent, 22.dp)
                        } else {
                            Box(Modifier.width(3.dp).height(14.dp).clip(RoundedCornerShape(2.dp)).background(accent))
                        }
                        Text(
                            row.channel.name,
                            style = KobappTheme.type.bodySm.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.textHi,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (row.channel.hd) LiveBadge("HD", accent)
                    }
                    // The window is aligned to the half hour, so it reaches back before now and
                    // the feed answers with programmes that have already ended. Derived so a
                    // tick only recomposes the row whose lineup it actually changes.
                    val upcoming by remember(row.entries, clock) {
                        derivedStateOf { row.entries.stillRunning(clock.now).take(6) }
                    }
                    if (upcoming.isEmpty()) {
                        // Dropping what has already finished can empty a sparse channel's
                        // row. Say so rather than leaving a blank strip under its header.
                        Text(
                            "No guide data",
                            style = KobappTheme.type.caption,
                            color = colors.textXlo,
                            modifier = Modifier.padding(horizontal = gutter),
                        )
                    } else {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(dimens.s2),
                            contentPadding = PaddingValues(horizontal = gutter),
                        ) {
                            items(upcoming, key = { it.id }) { entry ->
                                val key = "pe.${entry.id}"
                                PhoneGuideCard(
                                    entry = entry,
                                    clock = clock,
                                    accent = accent,
                                    key = key,
                                    preferredEntry = key == entryKey,
                                    onClick = {
                                        onSelect(LiveGuideSelection(row.channel, entry))
                                        // What is on air plays; what is not opens where
                                        // it can be recorded.
                                        if (entry.isAiringAt(clock.now)) onWatch(row.channel)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PhoneGuideCard(
    entry: LiveGuideEntry,
    clock: GuideClock,
    accent: Color,
    key: String,
    preferredEntry: Boolean,
    onClick: () -> Unit,
) {
    val colors = KobappTheme.colors
    val focusState = rememberTvFocusState()
    val onAir by remember(entry, clock) { derivedStateOf { entry.isAiringAt(clock.now) } }
    Box(
        modifier = Modifier
            .width(CardWidth)
            .height(CardHeight)
            .focusKey(key, preferredEntry = preferredEntry)
            .tvFocusGlow(focusState, cornerRadiusDp = 11f, scale = 1.02f)
            .clip(RoundedCornerShape(11.dp))
            .border(1.dp, if (onAir) accent else colors.lineSoft, RoundedCornerShape(11.dp))
            .tvClickable(focusState, onClick),
    ) {
        GuideArtwork(
            entry = entry,
            tint = if (onAir) lerp(colors.elev1, accent, 0.14f) else colors.elev1,
            modifier = Modifier.matchParentSize(),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .matchParentSize()
                .padding(horizontal = 11.dp, vertical = 9.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (onAir) {
                    LiveBadge("NOW", accent)
                } else {
                    // Over artwork: textLo against a scrimmed still is the low-contrast
                    // pair, and it is the one thing on the card a viewer scans for.
                    Text(
                        parseLiveInstant(entry.startTime)?.localHhMm() ?: "",
                        style = KobappTheme.type.caption.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.textHi,
                    )
                }
                val meta = listOfNotNull(entry.episodeNumber, entry.genres.firstOrNull()).joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Text(
                        meta,
                        style = KobappTheme.type.caption,
                        color = colors.textMd,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                entry.title,
                style = KobappTheme.type.caption.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textHi,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val pct = entry.progressPct
            Box(Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)).background(colors.elev3)) {
                if (onAir && pct != null) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth((pct / 100f).coerceIn(0f, 1f))
                            .clip(RoundedCornerShape(1.dp))
                            .background(accent),
                    )
                }
            }
        }
    }
}

@Composable
private fun WatchingPill(channel: LiveChannel, accent: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = KobappTheme.colors
    val focusState = rememberTvFocusState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        modifier = modifier
            .fillMaxWidth()
            .focusKey("watching", entryEligible = false)
            .tvFocusGlow(focusState, cornerRadiusDp = 13f, scale = 1.01f)
            .clip(RoundedCornerShape(13.dp))
            .background(colors.elev1)
            .border(1.dp, colors.line, RoundedCornerShape(13.dp))
            .tvClickable(focusState, onClick)
            .padding(11.dp),
    ) {
        ChannelLogo(channel, accent, 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("WATCHING", style = KobappTheme.type.labelSm, color = accent)
            Text(
                channel.now?.title ?: channel.name,
                style = KobappTheme.type.caption.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textHi,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.PlayArrow, contentDescription = "Watch", tint = colors.bg, modifier = Modifier.size(18.dp))
        }
    }
}
