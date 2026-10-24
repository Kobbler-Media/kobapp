package dev.kobbler.app.ui.dashboard.livetv

import androidx.compose.foundation.background
import dev.kobbler.app.ui.scrollsUnderTopChrome
import dev.kobbler.app.ui.PinnedHeader
import dev.kobbler.app.ui.LocalTopChromeOverlap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.LiveChannel
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.EmptyState
import dev.kobbler.app.ui.components.rememberTvFocusState
import dev.kobbler.app.ui.components.tvAutoGlow
import dev.kobbler.app.ui.components.tvClickable
import dev.kobbler.app.ui.components.tvFocusGlow
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup

@Composable
fun LiveChannelsList(
    channels: List<LiveChannel>,
    total: Int,
    loading: Boolean,
    accent: Color,
    gutter: Dp,
    entryKey: String?,
    modifier: Modifier = Modifier,
    facetStrip: @Composable () -> Unit,
    onLoadMore: () -> Unit,
    onChannelClick: (LiveChannel) -> Unit,
    onWatch: (LiveChannel) -> Unit,
    onToggleFavourite: (LiveChannel) -> Unit,
) {
    val colors = KobappTheme.colors
    val dimens = KobappTheme.dimens
    val listState = rememberLazyListState()
    val focus = remember { GuideFocus() }
    CenterFocusedRow(listState, focus, topRow = 0)

    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= listState.layoutInfo.totalItemsCount - 5 && channels.size < total
        }
    }
    LaunchedEffect(shouldLoadMore) { if (shouldLoadMore) onLoadMore() }

    PinnedHeader(
        header = { Box(Modifier.padding(horizontal = gutter, vertical = dimens.s3)) { facetStrip() } },
        modifier = modifier.fillMaxSize(),
    ) {
        if (channels.isEmpty() && !loading) {
            EmptyState(
                icon = Icons.Outlined.Tv,
                title = "No channels",
                subtitle = "Run a channel scan on the tuner to populate the lineup.",
            )
            return@PinnedHeader
        }

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = LocalTopChromeOverlap.current, start = gutter, end = gutter, bottom = dimens.s6),
            verticalArrangement = Arrangement.spacedBy(dimens.s1),
            modifier = Modifier.scrollsUnderTopChrome(listState).fillMaxSize(),
        ) {
            itemsIndexed(channels, key = { _, it -> it.id }) { index, channel ->
                val key = "lch.${channel.id}"
                LiveChannelRow(
                    channel = channel,
                    accent = accent,
                    key = key,
                    preferredEntry = key == entryKey,
                    onFocused = { focus.lazyIndex = index },
                    onClick = { onChannelClick(channel) },
                    onWatch = { onWatch(channel) },
                    onToggleFavourite = { onToggleFavourite(channel) },
                )
            }
            if (loading) {
                item("loading") {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = accent, modifier = Modifier.size(28.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveChannelRow(
    channel: LiveChannel,
    accent: Color,
    key: String,
    preferredEntry: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onWatch: () -> Unit,
    onToggleFavourite: () -> Unit,
) {
    val colors = KobappTheme.colors
    val dimens = KobappTheme.dimens
    val focusState = rememberTvFocusState()
    // Favourite and play are siblings of the row body, not children: a focusable
    // ancestor swallows a D-pad press aimed at its own descendants.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusKeyGroup(key)
            .onFocusChanged { if (it.hasFocus) onFocused() }
            .focusGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .focusKey("row", preferredEntry = preferredEntry)
                .tvFocusGlow(focusState, cornerRadiusDp = 12f, scale = 1.01f)
                .clip(RoundedCornerShape(12.dp))
                .tvClickable(focusState, onClick)
                .padding(vertical = 10.dp, horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChannelLogo(channel, accent, 44.dp, numbered = true)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        channel.name,
                        style = KobappTheme.type.body.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.textHi,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    channel.tierLabel()?.let { LiveBadge(it, if (channel.hd) accent else colors.textLo) }
                }
                val now = channel.now?.title
                if (!now.isNullOrBlank()) {
                    Text(
                        now,
                        style = KobappTheme.type.caption,
                        color = accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val sub = listOfNotNull(
                    channel.network,
                    channel.muxLabel,
                    if (channel.hasGuide) "${channel.guideEntryCount} guide entries" else "no guide",
                ).joinToString(" · ")
                Text(
                    sub,
                    style = KobappTheme.type.caption,
                    color = colors.textMd,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(dimens.s2))
        Box(
            modifier = Modifier
                .size(36.dp)
                .focusKey("favourite")
                .tvAutoGlow(cornerRadiusDp = 8f, scale = 1.08f)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onToggleFavourite),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (channel.favourite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = if (channel.favourite) "Remove favourite" else "Add favourite",
                tint = if (channel.favourite) accent else colors.textLo,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(dimens.s1))
        Box(
            modifier = Modifier
                .size(36.dp)
                .focusKey("play")
                .tvAutoGlow(cornerRadiusDp = 999f, scale = 1.08f)
                .clip(RoundedCornerShape(999.dp))
                .background(lerp(colors.elev2, accent, 0.18f))
                .clickable(onClick = onWatch),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.PlayArrow, contentDescription = "Watch", tint = accent, modifier = Modifier.size(18.dp))
        }
    }
}
