package dev.kobbler.app.ui.dashboard

import androidx.compose.foundation.background
import dev.kobbler.app.ui.scrollsUnderTopChrome
import dev.kobbler.app.ui.PinnedHeader
import dev.kobbler.app.ui.LocalTopChromeOverlap
import dev.kobbler.app.ui.components.ListSkeleton
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.LiveLeases
import dev.kobbler.app.data.LiveRecording
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.AsyncContent
import dev.kobbler.app.ui.components.BrowseHeader
import dev.kobbler.app.ui.components.EmptyState
import dev.kobbler.app.ui.components.KobButton
import dev.kobbler.app.ui.components.KobButtonStyle
import dev.kobbler.app.ui.components.rememberCached
import dev.kobbler.app.ui.components.rememberTvFocusState
import dev.kobbler.app.ui.components.tvClickable
import dev.kobbler.app.ui.components.tvFocusGlow
import dev.kobbler.app.ui.dashboard.livetv.CenterFocusedRow
import dev.kobbler.app.ui.dashboard.livetv.GuideFocus
import dev.kobbler.app.ui.dashboard.livetv.LiveBadge
import dev.kobbler.app.ui.dashboard.livetv.LiveThumb
import dev.kobbler.app.ui.dashboard.livetv.RecordingDot
import dev.kobbler.app.ui.dashboard.livetv.hhmmRange
import dev.kobbler.app.ui.dashboard.livetv.localHhMm
import dev.kobbler.app.ui.dashboard.livetv.minutesLabel
import dev.kobbler.app.ui.dashboard.livetv.nowInstant
import dev.kobbler.app.ui.dashboard.livetv.parseLiveInstant
import dev.kobbler.app.ui.dashboard.livetv.sizeLabel
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private const val TimelineHours = 6
private val LaneLabelWidth = 64.dp
private val LaneHeight = 44.dp

internal data class LiveRecordingsData(
    val recordings: List<LiveRecording>,
    val total: Int,
    val leases: LiveLeases?,
)

private enum class LaneBandKind { Recording, Watching, Moved }

private data class LaneBand(
    val startMs: Long,
    val endMs: Long,
    val label: String,
    val kind: LaneBandKind,
)

@Composable
fun LiveRecordingsScreen(
    api: KobblerApi,
    modifier: Modifier = Modifier,
    formFactor: FormFactor = FormFactor.Desktop,
    showHeader: Boolean = true,
    onRecordingClick: (String) -> Unit = {},
    onSeriesRulesClick: () -> Unit = {},
) {
    val (state, retry) = rememberCached("livetv-recordings") {
        coroutineScope {
            val leases = async { runCatching { api.getLiveLeases() }.getOrNull() }
            val page = api.getLiveRecordings(limit = 200)
            LiveRecordingsData(page.recordings, page.total, leases.await())
        }
    }

    AsyncContent(state, skeleton = { ListSkeleton(title = showHeader) }, onRetry = retry, modifier = modifier) { data ->
        val colors = KobappTheme.colors
        val dimens = KobappTheme.dimens
        val accent = colors.accLive
        val compact = formFactor == FormFactor.Phone
        val gutter = when (formFactor) {
            FormFactor.TV -> dimens.pageGutterTv
            FormFactor.Phone -> dimens.pageGutterPhone
            else -> dimens.pageGutter
        }
        val listState = rememberLazyListState()
        val focus = remember { GuideFocus() }
        CenterFocusedRow(listState, focus, topRow = 0)

        val inProgress = data.recordings.filter { it.isInProgress }
        val queued = data.recordings.filter { it.status == "scheduled" }
        val bytes = data.recordings.sumOf { it.sizeBytes }
        val counts = listOfNotNull(
            "${inProgress.size} recording now",
            "${queued.size} queued",
            sizeLabel(bytes).takeIf { it.isNotEmpty() }?.let { "$it used" },
        ).joinToString(" · ")

        PinnedHeader(
            header = {
                Column {
                    if (showHeader) {
                        Box(Modifier.padding(horizontal = gutter, vertical = dimens.s4)) {
                            BrowseHeader(
                                title = "Recordings",
                                accent = accent,
                                countLine = counts,
                                compact = compact,
                                trailing = {
                                    KobButton(
                                        text = "Series rules",
                                        onClick = onSeriesRulesClick,
                                        style = KobButtonStyle.Default,
                                        modifier = Modifier.focusKey("seriesrules", entryEligible = false),
                                    )
                                },
                            )
                        }
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = gutter).padding(top = dimens.s3),
                        ) {
                            Text(counts, style = KobappTheme.type.bodySm, color = colors.textMd, modifier = Modifier.weight(1f))
                            KobButton(
                                text = "Series rules",
                                onClick = onSeriesRulesClick,
                                style = KobButtonStyle.Default,
                                modifier = Modifier.focusKey("seriesrules", entryEligible = false),
                            )
                        }
                    }

                    TunerLeaseTimeline(
                        recordings = data.recordings,
                        leases = data.leases,
                        accent = accent,
                        modifier = Modifier.padding(horizontal = gutter).padding(top = dimens.s3),
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            if (data.recordings.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.FiberManualRecord,
                    title = "No recordings",
                    subtitle = "Record a programme from the guide, or set up a series rule.",
                )
                return@PinnedHeader
            }

            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = gutter, end = gutter, top = dimens.s3 + LocalTopChromeOverlap.current, bottom = dimens.s6),
                verticalArrangement = Arrangement.spacedBy(dimens.s2),
                modifier = Modifier.scrollsUnderTopChrome(listState).fillMaxSize(),
            ) {
                itemsIndexed(data.recordings, key = { _, it -> it.id }) { index, rec ->
                    RecordingRow(
                        recording = rec,
                        accent = accent,
                        onFocused = { focus.lazyIndex = index },
                        preferredEntry = index == 0,
                        onClick = { onRecordingClick(rec.id) },
                    )
                }
            }
        }
    }
}

/**
 * Four tuner rows across one time axis. A lease is held by *either* a viewer or a
 * recording, and a recording outranks a viewer — so a band that had to move shows in its
 * own colour with the reason spelled out below rather than silently disappearing.
 */
@Composable
private fun TunerLeaseTimeline(
    recordings: List<LiveRecording>,
    leases: LiveLeases?,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val colors = KobappTheme.colors
    val dimens = KobappTheme.dimens
    val density = LocalDensity.current
    val laneCount = (leases?.total ?: recordings.mapNotNull { it.tunerIndex }.maxOrNull()?.plus(1) ?: 0)
        .coerceAtLeast(0)
    if (laneCount == 0) return

    val now = nowInstant()
    val windowStart = Instant.fromEpochMilliseconds(
        (now - 90.minutes).toEpochMilliseconds() / 3_600_000L * 3_600_000L,
    )
    val windowEnd = windowStart + TimelineHours.hours
    val startMs = windowStart.toEpochMilliseconds()
    val spanMs = (windowEnd - windowStart).inWholeMilliseconds

    val lanes = remember(recordings, leases, startMs) {
        buildLanes(laneCount, recordings, leases, startMs, startMs + spanMs)
    }
    val moved = recordings.firstOrNull { it.movedToGuideId != null }

    var trackPx by remember { mutableIntStateOf(0) }

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.elev1)
            .border(1.dp, colors.line, RoundedCornerShape(12.dp))
            .padding(horizontal = dimens.s4, vertical = dimens.s3),
        verticalArrangement = Arrangement.spacedBy(dimens.s2),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dimens.s3)) {
            Text("Tuner leases", style = KobappTheme.type.bodySm.copy(fontWeight = FontWeight.SemiBold), color = colors.textHi)
            Text(
                "${leases?.inUse ?: lanes.count { it.isNotEmpty() }} of $laneCount in use",
                style = KobappTheme.type.caption,
                color = colors.textMd,
            )
            Spacer(Modifier.weight(1f))
            LaneLegend(colors.err, "recording")
            LaneLegend(accent, "watching")
            LaneLegend(colors.accWanted, "moved by a clash")
        }

        Row {
            Spacer(Modifier.width(LaneLabelWidth))
            Row(Modifier.weight(1f)) {
                repeat(TimelineHours) { i ->
                    Text(
                        (windowStart + i.hours).localHhMm(),
                        style = KobappTheme.type.bodySm,
                        color = colors.textXlo,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        lanes.forEachIndexed { index, bands ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().height(LaneHeight),
            ) {
                Text(
                    "tuner$index",
                    style = KobappTheme.type.bodySm,
                    color = colors.textMd,
                    modifier = Modifier.width(LaneLabelWidth),
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .onSizeChanged { if (it.width > 0) trackPx = it.width },
                ) {
                    if (bands.isEmpty()) {
                        Text(
                            "free",
                            style = KobappTheme.type.caption,
                            color = colors.textXlo,
                            modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp),
                        )
                    }
                    if (trackPx > 0) {
                        bands.forEach { band ->
                            val leftPx = ((band.startMs - startMs).toDouble() / spanMs * trackPx).toInt()
                            val widthPx = ((band.endMs - band.startMs).toDouble() / spanMs * trackPx)
                                .toInt().coerceAtLeast(4)
                            val tint = when (band.kind) {
                                LaneBandKind.Recording -> colors.err
                                LaneBandKind.Watching -> accent
                                LaneBandKind.Moved -> colors.accWanted
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .offset { IntOffset(leftPx, 0) }
                                    .width(with(density) { widthPx.toDp() })
                                    .height(34.dp)
                                    .align(Alignment.CenterStart)
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(lerp(colors.elev1, tint, 0.18f))
                                    .border(1.dp, tint, RoundedCornerShape(7.dp))
                                    .padding(horizontal = 9.dp),
                            ) {
                                Text(
                                    band.label,
                                    style = KobappTheme.type.caption.copy(fontWeight = FontWeight.SemiBold),
                                    color = colors.textHi,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        val nowPx = ((now.toEpochMilliseconds() - startMs).toDouble() / spanMs * trackPx).toInt()
                        if (nowPx in 0..trackPx) {
                            Box(
                                Modifier
                                    .offset { IntOffset(nowPx, 0) }
                                    .width(2.dp)
                                    .fillMaxHeight()
                                    .background(accent),
                            )
                        }
                    }
                }
            }
        }

        if (moved != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dimens.s2),
                modifier = Modifier.padding(top = dimens.s1),
            ) {
                Icon(Icons.Outlined.Replay, null, tint = colors.accWanted, modifier = Modifier.size(14.dp))
                Text(
                    buildString {
                        append(moved.title)
                        val at = parseLiveInstant(moved.scheduledStart)?.localHhMm()
                        if (at != null) append(" clashed at $at")
                        append(" with every tuner busy. It airs again inside the guide window, so the rule took the repeat instead of dropping the episode.")
                    },
                    style = KobappTheme.type.caption,
                    color = colors.textMd,
                )
            }
        }
    }
}

@Composable
private fun LaneLegend(tint: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .size(9.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(lerp(KobappTheme.colors.elev1, tint, 0.2f))
                .border(1.dp, tint, RoundedCornerShape(3.dp)),
        )
        Text(label, style = KobappTheme.type.caption, color = KobappTheme.colors.textMd)
    }
}

private fun buildLanes(
    laneCount: Int,
    recordings: List<LiveRecording>,
    leases: LiveLeases?,
    windowStartMs: Long,
    windowEndMs: Long,
): List<List<LaneBand>> {
    val lanes = List(laneCount) { mutableListOf<LaneBand>() }
    fun clampInto(lane: Int, startIso: String?, endIso: String?, label: String, kind: LaneBandKind) {
        if (lane !in 0 until laneCount) return
        val s = parseLiveInstant(startIso)?.toEpochMilliseconds() ?: return
        val e = parseLiveInstant(endIso)?.toEpochMilliseconds() ?: (windowEndMs)
        if (e <= windowStartMs || s >= windowEndMs) return
        lanes[lane] += LaneBand(s.coerceAtLeast(windowStartMs), e.coerceAtMost(windowEndMs), label, kind)
    }
    recordings.forEach { rec ->
        val lane = rec.tunerIndex ?: return@forEach
        if (rec.status !in setOf("recording", "scheduled", "moved")) return@forEach
        val kind = if (rec.movedToGuideId != null) LaneBandKind.Moved else LaneBandKind.Recording
        val label = listOfNotNull("REC ${rec.title}", rec.channelName).joinToString(" · ")
        clampInto(lane, rec.actualStart ?: rec.scheduledStart, rec.scheduledEnd, label, kind)
    }
    leases?.items?.forEach { lease ->
        if (lease.holderKind == "recording") return@forEach
        val label = listOfNotNull(lease.channelName, "you").joinToString(" · ")
        clampInto(lease.tunerIndex, lease.acquiredAt, lease.expiresAt, label, LaneBandKind.Watching)
    }
    return lanes
}

@Composable
private fun RecordingRow(
    recording: LiveRecording,
    accent: Color,
    preferredEntry: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    val colors = KobappTheme.colors
    val dimens = KobappTheme.dimens
    val focusState = rememberTvFocusState()
    val live = recording.isInProgress
    // The action is a sibling of the row body, not a child: a focusable ancestor
    // swallows a D-pad press aimed at its own descendants.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .focusKeyGroup("rec.${recording.id}")
            .onFocusChanged { if (it.hasFocus) onFocused() }
            .clip(RoundedCornerShape(11.dp))
            .background(colors.elev1)
            .border(1.dp, if (live) colors.err else colors.lineSoft, RoundedCornerShape(11.dp))
            .focusGroup()
            .padding(horizontal = dimens.s3, vertical = dimens.s3),
    ) {
        LiveThumb(recording.imageUrl, width = 76.dp, height = 44.dp)
        Spacer(Modifier.width(dimens.s3))
        Column(
            Modifier
                .weight(1f)
                .focusKey("row", preferredEntry = preferredEntry)
                .tvFocusGlow(focusState, cornerRadiusDp = 11f, scale = 1.01f)
                .clip(RoundedCornerShape(11.dp))
                .tvClickable(focusState, onClick),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                if (live) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RecordingDot(colors.err, 6.dp)
                        Text("REC", style = KobappTheme.type.labelSm, color = colors.err)
                    }
                }
                Text(
                    recording.title,
                    style = KobappTheme.type.body.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.textHi,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (recording.status == "failed") LiveBadge("FAILED", colors.err)
                if (recording.status == "scheduled") LiveBadge("QUEUED", colors.textLo)
            }
            Text(
                listOfNotNull(
                    recording.episodeNumber,
                    recording.channelName,
                    hhmmRange(recording.scheduledStart, recording.scheduledEnd).takeIf { it.isNotEmpty() },
                ).joinToString(" · "),
                style = KobappTheme.type.caption,
                color = colors.textMd,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                recordingNote(recording),
                style = KobappTheme.type.caption,
                color = colors.textLo,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            LinearProgress(recording, if (live) colors.err else colors.elev3)
        }
        Spacer(Modifier.width(dimens.s3))
        KobButton(
            text = if (live) "Watch from start" else "Play",
            onClick = onClick,
            style = if (live) KobButtonStyle.Accent else KobButtonStyle.Default,
            accent = accent,
            enabled = live || recording.status == "completed",
            leading = { Icon(Icons.Outlined.PlayArrow, null, modifier = Modifier.size(14.dp)) },
            modifier = Modifier.focusKey("play"),
        )
    }
}

@Composable
private fun LinearProgress(recording: LiveRecording, tint: Color) {
    val colors = KobappTheme.colors
    val fraction = when {
        recording.isInProgress && recording.durationSeconds > 0 ->
            (recording.secondsOnDisk.toFloat() / recording.durationSeconds).coerceIn(0f, 1f)
        recording.status == "completed" -> 1f
        else -> 0f
    }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 5.dp)
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(colors.elev3),
    ) {
        if (fraction > 0f) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).clip(RoundedCornerShape(2.dp)).background(tint))
        }
    }
}

private fun recordingNote(recording: LiveRecording): String = when {
    recording.isInProgress -> {
        val onDisk = minutesLabel(recording.secondsOnDisk)
        if (recording.playableFromStart && onDisk.isNotEmpty()) {
            "watch from the start — $onDisk already on disk"
        } else {
            "watch from the start"
        }
    }
    recording.status == "failed" -> recording.error ?: "recording failed"
    else -> listOfNotNull(
        minutesLabel(recording.durationSeconds).takeIf { it.isNotEmpty() },
        when {
            recording.adBreaks.isEmpty() -> "no ads detected"
            else -> "${recording.adBreaks.size} ad breaks marked"
        },
        sizeLabel(recording.sizeBytes).takeIf { it.isNotEmpty() },
        recording.provenance,
    ).joinToString(" · ")
}
