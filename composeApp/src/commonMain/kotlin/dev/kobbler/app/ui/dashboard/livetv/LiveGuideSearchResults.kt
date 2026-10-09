package dev.kobbler.app.ui.dashboard.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.LiveChannel
import dev.kobbler.app.data.LiveGuideChannel
import dev.kobbler.app.data.LiveGuideEntry
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.EmptyState
import dev.kobbler.app.ui.components.rememberTvFocusState
import dev.kobbler.app.ui.components.tvAutoGlow
import dev.kobbler.app.ui.components.tvClickable
import dev.kobbler.app.ui.components.tvFocusGlow
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup
import kotlin.time.Instant

/**
 * Guide search results, flat and chronological.
 *
 * A query has nothing to do with the guide's time window — a programme is worth finding
 * wherever in the schedule it sits — so results leave the grid's time axis behind and
 * sort across every channel by when the programme airs.
 */
@Composable
fun LiveGuideSearchResults(
    results: List<LiveGuideChannel>,
    query: String,
    loading: Boolean,
    clock: GuideClock,
    accent: Color,
    gutter: Dp,
    modifier: Modifier = Modifier,
    onSelect: (LiveGuideSelection) -> Unit,
    onWatch: (LiveChannel) -> Unit,
) {
    val colors = KobappTheme.colors
    val dimens = KobappTheme.dimens
    val listState = rememberLazyListState()
    val focus = remember { GuideFocus() }
    CenterFocusedRow(listState, focus, topRow = 0)

    val hits = remember(results) {
        results
            .flatMap { row -> row.entries.map { LiveGuideSelection(row.channel, it) } }
            .sortedBy { parseLiveInstant(it.entry.startTime) ?: Instant.DISTANT_FUTURE }
    }

    Column(modifier.fillMaxSize().background(colors.bg)) {
        if (hits.isEmpty()) {
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = accent, modifier = Modifier.size(28.dp))
                }
            } else {
                EmptyState(
                    icon = Icons.Outlined.SearchOff,
                    title = "Nothing matching “$query”",
                    subtitle = "The guide reaches $GuideFutureHours h ahead — a programme " +
                        "further out than that has not been published yet.",
                )
            }
            return@Column
        }

        Text(
            "${hits.size} ${if (hits.size == 1) "airing" else "airings"} in the next $GuideFutureHours h",
            style = KobappTheme.type.caption,
            color = colors.textLo,
            modifier = Modifier.padding(horizontal = gutter).padding(bottom = dimens.s2),
        )

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = gutter, end = gutter, bottom = dimens.s6),
            verticalArrangement = Arrangement.spacedBy(dimens.s1),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(hits, key = { _, it -> it.entry.id }) { index, hit ->
                SearchResultRow(
                    clock = clock,
                    channel = hit.channel,
                    entry = hit.entry,
                    accent = accent,
                    preferredEntry = index == 0,
                    onFocused = { focus.lazyIndex = index },
                    onClick = { onSelect(hit) },
                    onWatch = { onWatch(hit.channel) },
                )
            }
        }
    }
}

@Composable
private fun SearchResultRow(
    clock: GuideClock,
    channel: LiveChannel,
    entry: LiveGuideEntry,
    accent: Color,
    preferredEntry: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onWatch: () -> Unit,
) {
    val colors = KobappTheme.colors
    val dimens = KobappTheme.dimens
    val focusState = rememberTvFocusState()
    val onAir = entry.isAiringAt(clock.now)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .focusKeyGroup("gs.${entry.id}")
            .onFocusChanged { if (it.hasFocus) onFocused() }
            .clip(RoundedCornerShape(10.dp))
            .background(if (onAir) lerp(colors.elev1, accent, 0.08f) else colors.elev1)
            .border(1.dp, if (onAir) accent else colors.lineSoft, RoundedCornerShape(10.dp))
            .padding(horizontal = dimens.s3, vertical = dimens.s2),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .focusKey("row", preferredEntry = preferredEntry)
                .tvFocusGlow(focusState, cornerRadiusDp = 10f, scale = 1.01f)
                .clip(RoundedCornerShape(10.dp))
                .tvClickable(focusState, onClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GuideWhen(entry, clock.now, accent)
            Spacer(Modifier.width(dimens.s3))
            LiveThumb(entry.imageUrl ?: entry.posterUrl, width = 72.dp, height = 42.dp)
            Spacer(Modifier.width(dimens.s3))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        entry.title,
                        style = KobappTheme.type.bodySm.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.textHi,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (onAir) LiveBadge("NOW", accent)
                }
                Text(
                    listOfNotNull(
                        channel.lcn?.let { "$it · ${channel.name}" } ?: channel.name,
                        entry.episodeNumber,
                        entry.genres.firstOrNull(),
                    ).joinToString(" · "),
                    style = KobappTheme.type.caption,
                    color = colors.textMd,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onAir) {
            Spacer(Modifier.width(dimens.s2))
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .focusKey("play")
                    .tvAutoGlow(cornerRadiusDp = 999f, scale = 1.08f)
                    .clip(RoundedCornerShape(999.dp))
                    .background(lerp(colors.elev2, accent, 0.18f))
                    .tvClickable(rememberTvFocusState(), onWatch),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.PlayArrow, "Watch", tint = accent, modifier = Modifier.size(18.dp))
            }
        }
    }
}
