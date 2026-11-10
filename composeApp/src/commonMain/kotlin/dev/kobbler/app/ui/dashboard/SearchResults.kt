package dev.kobbler.app.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.DiscoverItem
import dev.kobbler.app.data.IptvChannel
import dev.kobbler.app.data.LiveChannel
import dev.kobbler.app.data.SearchHitDto
import dev.kobbler.app.theme.KobappColors
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.SectionHeading
import dev.kobbler.app.ui.components.tvAutoGlow
import dev.kobbler.kobfocus.focusKey

internal fun LazyGridScope.fullWidthItem(key: Any, content: @Composable LazyGridItemScope.() -> Unit) =
    item(key = key, span = { GridItemSpan(maxLineSpan) }, content = content)

/** Must emit exactly [SearchLayout.topItemCount] and [SearchSection.itemCount] items, or [searchJumps] lands short. */
internal fun LazyGridScope.searchResultSections(
    layout: SearchLayout,
    expanded: SnapshotStateMap<String, Boolean>,
    apiUrl: String,
    colors: KobappColors,
    onResultClick: (SearchHitDto) -> Unit,
    onDiscoverClick: (DiscoverItem) -> Unit,
    onLiveClick: (LiveChannel) -> Unit,
    onIptvClick: (IptvChannel) -> Unit,
) {
    val rows: LazyGridScope.(SearchSection) -> Unit = { section ->
        sectionRows(section, apiUrl, colors, onResultClick, onDiscoverClick, onLiveClick, onIptvClick)
    }
    if (layout.top.isNotEmpty()) {
        fullWidthItem(key = "top-results-head") { SectionHeader("Top results", colors.accHome, count = null) }
        layout.top.forEach { rows(it) }
    }
    layout.sections.forEach { section ->
        val isExpanded = expanded[section.expandKey] == true
        val accent = sectionAccent(section, colors)
        fullWidthItem(key = "section-${section.expandKey}") { SectionHeader(section.title, accent, section.size) }
        rows(section.slice(0, section.visibleCount(isExpanded)))
        if (section.hasMoreRow()) {
            fullWidthItem(key = "more-${section.expandKey}") {
                MoreRow(
                    expanded = isExpanded,
                    remaining = section.size - SearchSectionTopN,
                    accent = accent,
                    key = "more.${section.expandKey}",
                    onClick = { expanded[section.expandKey] = !isExpanded },
                )
            }
        }
    }
}

private fun LazyGridScope.sectionRows(
    section: SearchSection,
    apiUrl: String,
    colors: KobappColors,
    onResultClick: (SearchHitDto) -> Unit,
    onDiscoverClick: (DiscoverItem) -> Unit,
    onLiveClick: (LiveChannel) -> Unit,
    onIptvClick: (IptvChannel) -> Unit,
) {
    when (section) {
        is SearchSection.Library -> items(section.hits, key = { "lib-${it.type}-${it.id}" }) { hit ->
            SearchResultRow(hit = hit, apiUrl = apiUrl, accent = typeAccent(hit.sectionType, colors), key = "lib.${hit.type}.${hit.id}", onClick = { onResultClick(hit) })
        }
        is SearchSection.Discover -> {
            val accent = sectionAccent(section, colors)
            items(section.items, key = { "disc-${it.provider}-${it.providerId}" }) { item ->
                DiscoverOverlayCard(item = item, accent = accent, key = "disc.${item.provider}.${item.providerId}", onClick = { onDiscoverClick(item) })
            }
        }
        is SearchSection.LiveChannels -> liveChannelRows(section.channels, onLiveClick)
        is SearchSection.LiveAirings -> liveAiringRows(section.airings) { onLiveClick(it.channel) }
        is SearchSection.IptvChannels -> iptvChannelRows(section.channels, onIptvClick)
    }
}

private fun sectionAccent(section: SearchSection, colors: KobappColors): Color = when (section) {
    is SearchSection.Library -> typeAccent(section.type, colors)
    is SearchSection.Discover -> when (section.mediaType) {
        "movie" -> colors.accMovies
        "tv" -> colors.accTv
        "book" -> colors.accBooks
        else -> colors.textLo
    }
    is SearchSection.LiveChannels, is SearchSection.LiveAirings -> colors.accLive
    is SearchSection.IptvChannels -> colors.accIptv
}

@Composable
internal fun SectionHeader(title: String, accent: Color, count: Int?) {
    val colors = KobappTheme.colors
    Column {
        Spacer(Modifier.height(14.dp))
        SectionHeading(
            title = title,
            accent = accent,
            trailing = count?.let { { Text("$it", style = KobappTheme.type.bodySm, color = colors.textLo) } },
        )
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun MoreRow(expanded: Boolean, remaining: Int, accent: Color, key: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusKey(key)
            .tvAutoGlow(cornerRadiusDp = 8f, scale = 1f)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            null,
            tint = accent,
            modifier = Modifier.size(18.dp),
        )
        Text(
            if (expanded) "Show less" else "More ($remaining)",
            style = KobappTheme.type.caption.copy(fontWeight = FontWeight.SemiBold),
            color = accent,
        )
    }
}

@Composable
internal fun SectionLoadingRow(label: String) {
    val colors = KobappTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = colors.textLo,
        )
        Text(label, style = KobappTheme.type.caption, color = colors.textLo)
    }
}
