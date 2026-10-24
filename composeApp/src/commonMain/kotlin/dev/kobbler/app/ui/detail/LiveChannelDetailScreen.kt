package dev.kobbler.app.ui.detail

import androidx.compose.foundation.background
import dev.kobbler.app.ui.scrollsUnderTopChrome
import dev.kobbler.app.ui.safeHorizontalPadding
import dev.kobbler.app.ui.UnderStatusBar
import dev.kobbler.app.ui.PinnedHeader
import dev.kobbler.app.ui.LocalTopChromeOverlap
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.foundation.layout.RowScope
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.LiveChannel
import dev.kobbler.app.data.LiveGuideEntry
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.EmptyState
import dev.kobbler.app.ui.components.KobButton
import dev.kobbler.app.ui.components.KobButtonStyle
import dev.kobbler.app.ui.components.rememberTvFocusState
import dev.kobbler.app.ui.components.tvClickable
import dev.kobbler.app.ui.components.tvFocusGlow
import dev.kobbler.app.ui.dashboard.livetv.CenterFocusedRow
import dev.kobbler.app.ui.dashboard.livetv.ChannelLogo
import dev.kobbler.app.ui.dashboard.livetv.GuideClock
import dev.kobbler.app.ui.dashboard.livetv.GuideFocus
import dev.kobbler.app.ui.dashboard.livetv.GuideWhen
import dev.kobbler.app.ui.dashboard.livetv.LiveBadge
import dev.kobbler.app.ui.dashboard.livetv.isAiringAt
import dev.kobbler.app.ui.dashboard.livetv.rememberGuideClock
import dev.kobbler.app.ui.dashboard.livetv.LiveThumb
import dev.kobbler.app.ui.dashboard.livetv.minutesLabel
import dev.kobbler.app.ui.dashboard.livetv.tierLabel
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup
import kotlinx.coroutines.launch

/**
 * [onWatch] is where part 2 hands the channel to the player; it is the only affordance
 * here that needs a live session, so the rest of the page works without one.
 */
@Composable
fun LiveChannelDetailScreen(
    channel: LiveChannel,
    entries: List<LiveGuideEntry>,
    api: KobblerApi,
    modifier: Modifier = Modifier,
    formFactor: FormFactor = FormFactor.Desktop,
    onBack: () -> Unit = {},
    onWatch: (LiveChannel) -> Unit = {},
    onSeriesRule: ((LiveGuideEntry) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val colors = KobappTheme.colors
    val dimens = KobappTheme.dimens
    val accent = colors.accLive
    val compact = formFactor == FormFactor.Phone
    val gutter = when (formFactor) {
        FormFactor.TV -> dimens.pageGutterTv
        FormFactor.Phone -> dimens.pageGutterPhone
        else -> dimens.pageGutter
    }
    var favourite by remember(channel.id) { mutableStateOf(channel.favourite) }
    // Guide entry -> its recording. Cancelling needs the id, and a plain "recorded"
    // flag left the button as a dead label with no way back.
    var recordings by remember(channel.id) {
        mutableStateOf(entries.mapNotNull { e -> e.recordingId?.let { e.id to it } }.toMap())
    }
    var busy by remember(channel.id) { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val focus = remember { GuideFocus() }
    val clock = rememberGuideClock(active = true)
    CenterFocusedRow(listState, focus, topRow = 0)

    UnderStatusBar {
        PinnedHeader(
            header = {
                val identity: @Composable RowScope.() -> Unit = {
                    KobButton(
                        onClick = onBack,
                        style = KobButtonStyle.Ghost,
                        modifier = Modifier.focusKey("back", entryEligible = false),
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", modifier = Modifier.size(20.dp))
                    }
                    ChannelLogo(channel, accent, if (compact) 48.dp else 64.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                channel.name,
                                style = if (compact) KobappTheme.type.h2 else KobappTheme.type.h1,
                                color = colors.textHi,
                                maxLines = if (compact) 2 else 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            channel.tierLabel()?.let { LiveBadge(it, accent) }
                        }
                        // Mux frequency, codec pair and signal percentage are tuner diagnostics,
                        // not a description of the channel — the Settings tuner page is where they
                        // belong. What is left says which channel this is and what it looks like.
                        Text(
                            listOfNotNull(
                                channel.lcn?.let { "ch$it" },
                                channel.network,
                                channel.region,
                            ).joinToString(" · "),
                            style = KobappTheme.type.bodySm,
                            color = colors.textMd,
                        )
                    }
                }
                val actions: @Composable RowScope.() -> Unit = {
                    PrimaryAction(
                        icon = Icons.Outlined.PlayArrow,
                        label = "Watch",
                        onClick = { onWatch(channel) },
                        modifier = Modifier.focusKey("watch", preferredEntry = true),
                    )
                    IconAction(
                        icon = if (favourite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                        label = if (favourite) "Remove favourite" else "Add favourite",
                        tint = if (favourite) accent else colors.textHi,
                        accent = accent,
                        onClick = {
                            val next = !favourite
                            favourite = next
                            scope.launch { runCatching { api.setLiveChannelFavourite(channel.id, next) } }
                        },
                        modifier = Modifier.focusKey("favourite", entryEligible = false),
                    )
                }
                // Beside the actions a phone left the name a column a word wide.
                if (compact) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(dimens.s3),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = dimens.s2).focusKeyGroup("channelhead"),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dimens.s3), content = identity)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dimens.s3), content = actions)
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(dimens.s4),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = dimens.s2).focusKeyGroup("channelhead"),
                    ) {
                        identity()
                        actions()
                    }
                }
            },
            modifier = modifier.fillMaxSize().safeHorizontalPadding(),
        ) {
            if (entries.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.FiberManualRecord,
                    title = "No guide for this channel",
                    subtitle = "The tuner has not carried an EPG for ${channel.name} yet.",
                )
                return@PinnedHeader
            }

            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(top = LocalTopChromeOverlap.current, start = gutter, end = gutter, bottom = dimens.s6),
                verticalArrangement = Arrangement.spacedBy(dimens.s1),
                modifier = Modifier.scrollsUnderTopChrome().fillMaxSize(),
            ) {
                itemsIndexed(entries, key = { _, it -> it.id }) { index, entry ->
                    GuideEntryRow(
                        clock = clock,
                        entry = entry,
                        accent = accent,
                        recordingId = recordings[entry.id],
                        busy = busy == entry.id,
                        onFocused = { focus.lazyIndex = index },
                        onWatch = { onWatch(channel) },
                        onSeriesRule = onSeriesRule?.let { seed -> { seed(entry) } },
                        onRecord = {
                            busy = entry.id
                            scope.launch {
                                runCatching { api.recordLiveGuideEntry(entry.id) }
                                    .onSuccess { recordings = recordings + (entry.id to it.id) }
                                busy = null
                            }
                        },
                        onCancel = {
                            val recordingId = recordings[entry.id] ?: return@GuideEntryRow
                            busy = entry.id
                            scope.launch {
                                // A capture already running has to be stopped; one that has
                                // not started yet is simply dropped.
                                val stopped = if (entry.recordingStatus == "recording") {
                                    runCatching { api.stopLiveRecording(recordingId) }.isSuccess
                                } else {
                                    runCatching { api.deleteLiveRecording(recordingId) }.isSuccess
                                }
                                if (stopped) recordings = recordings - entry.id
                                busy = null
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun GuideEntryRow(
    clock: GuideClock,
    entry: LiveGuideEntry,
    accent: Color,
    recordingId: String?,
    busy: Boolean,
    onFocused: () -> Unit,
    onWatch: () -> Unit,
    onSeriesRule: (() -> Unit)?,
    onRecord: () -> Unit,
    onCancel: () -> Unit,
) {
    val recorded = recordingId != null
    val colors = KobappTheme.colors
    val dimens = KobappTheme.dimens
    val focusState = rememberTvFocusState()
    val onAir = entry.isAiringAt(clock.now)
    // Record is a sibling of the row body, not a child: a focusable ancestor swallows a
    // D-pad press aimed at its own descendants.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .focusKeyGroup("ge.${entry.id}")
            .onFocusChanged { if (it.hasFocus) onFocused() }
            .clip(RoundedCornerShape(10.dp))
            .background(if (onAir) lerp(colors.elev1, accent, 0.08f) else colors.elev1)
            .border(1.dp, if (onAir) accent else colors.lineSoft, RoundedCornerShape(10.dp))
            .focusGroup()
            .padding(horizontal = dimens.s3, vertical = dimens.s3),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .focusKey("row")
                .tvFocusGlow(focusState, cornerRadiusDp = 10f, scale = 1.01f)
                .clip(RoundedCornerShape(10.dp))
                // Only what is on air is playable; the rest of the row is still a
                // focus target, because Record sits inside it.
                .then(if (onAir) Modifier.tvClickable(focusState, onWatch) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GuideWhen(entry, clock.now, accent)
            Spacer(Modifier.width(dimens.s3))
            LiveThumb(entry.imageUrl ?: entry.posterUrl, width = 76.dp, height = 44.dp)
            Spacer(Modifier.width(dimens.s3))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        entry.title,
                        style = KobappTheme.type.body.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.textHi,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (onAir) LiveBadge("NOW", accent)
                    if (entry.repeatCount > 0) {
                        Text("+${entry.repeatCount} airings", style = KobappTheme.type.labelSm, color = colors.textXlo)
                    }
                }
                val sub = listOfNotNull(
                    entry.episodeNumber,
                    entry.episodeTitle,
                    entry.genres.firstOrNull(),
                    minutesLabel(entry.durationSeconds).takeIf { it.isNotEmpty() },
                ).joinToString(" · ")
                if (sub.isNotEmpty()) Text(sub, style = KobappTheme.type.caption, color = colors.textMd, maxLines = 1, overflow = TextOverflow.Ellipsis)
                entry.synopsis?.let {
                    Text(it, style = KobappTheme.type.caption, color = colors.textLo, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.width(dimens.s3))
        KobButton(
            text = if (recorded) "Cancel" else "Record",
            onClick = if (recorded) onCancel else onRecord,
            style = KobButtonStyle.Default,
            enabled = !busy,
            leading = {
                Icon(
                    if (recorded) Icons.Outlined.Close else Icons.Outlined.FiberManualRecord,
                    null,
                    tint = if (recorded) colors.textMd else colors.err,
                    modifier = Modifier.size(14.dp),
                )
            },
            modifier = Modifier.focusKey("record"),
        )
        if (onSeriesRule != null) {
            Spacer(Modifier.width(dimens.s2))
            IconAction(
                icon = Icons.Outlined.BookmarkAdd,
                label = "Record every episode of ${entry.title}",
                tint = colors.textHi,
                accent = accent,
                onClick = onSeriesRule,
                captioned = false,
                modifier = Modifier.focusKey("series", entryEligible = false),
            )
        }
    }
}
