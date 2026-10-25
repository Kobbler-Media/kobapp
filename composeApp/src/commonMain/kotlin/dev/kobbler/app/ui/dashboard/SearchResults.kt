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

/** Must emit exactly [SearchSection.itemCount] items per section, or [searchJumps] lands short. */
internal fun LazyGridScope.searchResultSections(
    sections: List<SearchSection>,
    expanded: SnapshotStateMap<String, Boolean>,
    apiUrl: String,
    colors: KobappColors,
    onResultClick: (SearchHitDto) -> Unit,
    onDiscoverClick: (DiscoverItem) -> Unit,
    onLiveClick: (LiveChannel) -> Unit,
    onIptvClick: (IptvChannel) -> Unit,
) {
    sections.forEach { section ->
        val isExpanded = expanded[section.expandKey] == true
        val visible = section.visibleCount(isExpanded)
        val accent = when (section) {
            is SearchSection.Library -> {
                librarySubsection(section.type, section.hits.take(visible), section.size, apiUrl, onResultClick, colors)
                typeAccent(section.type, colors)
            }
            is SearchSection.Discover -> {
                val accent = discoverAccent(section.mediaType, colors)
                discoverSubsection(section.mediaType, section.items.take(visible), section.size, accent, onDiscoverClick, colors)
                accent
            }
            is SearchSection.LiveChannels -> {
                liveChannelsSubsection(section.channels.take(visible), onLiveClick)
                colors.accLive
            }
            is SearchSection.LiveAirings -> {
                liveAiringsSubsection(section.airings.take(visible), section.size) { onLiveClick(it.channel) }
                colors.accLive
            }
            is SearchSection.IptvChannels -> {
                iptvChannelsSubsection(section.channels.take(visible), onIptvClick)
                colors.accIptv
            }
        }
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

private fun LazyGridScope.librarySubsection(
    type: String,
    visible: List<SearchHitDto>,
    total: Int,
    apiUrl: String,
    onResultClick: (SearchHitDto) -> Unit,
    colors: KobappColors,
) {
    fullWidthItem(key = "lib-section-$type") {
        SectionHeader(sectionTitle(type), typeAccent(type, colors), total)
    }
    items(visible, key = { "lib-${it.type}-${it.id}" }) { hit ->
        SearchResultRow(hit = hit, apiUrl = apiUrl, accent = typeAccent(hit.type, colors), key = "lib.${hit.type}.${hit.id}", onClick = { onResultClick(hit) })
    }
}

private fun LazyGridScope.discoverSubsection(
    mediaType: String,
    visible: List<DiscoverItem>,
    total: Int,
    accent: Color,
    onItemClick: (DiscoverItem) -> Unit,
    colors: KobappColors,
) {
    val label = when (mediaType) {
        "movie" -> "Discover · Movies"; "tv" -> "Discover · TV Shows"
        "book" -> "Discover · Books"; else -> "Discover · $mediaType"
    }
    fullWidthItem(key = "disc-section-$mediaType") {
        SectionHeader(label, accent, total)
    }
    items(visible, key = { "disc-${it.provider}-${it.providerId}" }) { item ->
        DiscoverOverlayCard(item = item, accent = accent, key = "disc.${item.provider}.${item.providerId}", onClick = { onItemClick(item) })
    }
}

private fun discoverAccent(mediaType: String, colors: KobappColors): Color = when (mediaType) {
    "movie" -> colors.accMovies
    "tv" -> colors.accTv
    "book" -> colors.accBooks
    else -> colors.textLo
}

@Composable
internal fun SectionHeader(title: String, accent: Color, count: Int) {
    val colors = KobappTheme.colors
    Column {
        Spacer(Modifier.height(14.dp))
        SectionHeading(
            title = title,
            accent = accent,
            trailing = { Text("$count", style = KobappTheme.type.bodySm, color = colors.textLo) },
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
