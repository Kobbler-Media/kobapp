package dev.kobbler.app.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import dev.kobbler.app.data.IptvChannel
import dev.kobbler.app.data.IptvEpgEntry
import dev.kobbler.app.data.IptvEpgFavouritesResponse
import dev.kobbler.app.data.IptvGroup
import dev.kobbler.app.data.IptvWatchMatch
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.LocalTopChromeOverlap
import dev.kobbler.app.ui.PinnedHeader
import dev.kobbler.app.ui.components.Async
import dev.kobbler.app.ui.components.AsyncContent
import dev.kobbler.app.ui.components.BrowseHeader
import dev.kobbler.app.ui.components.EmptyState
import dev.kobbler.app.ui.components.ListSkeleton
import dev.kobbler.app.ui.components.SegmentedFilter
import dev.kobbler.app.ui.components.rememberCached
import dev.kobbler.app.ui.dashboard.livetv.CenterFocusedRow
import dev.kobbler.app.ui.dashboard.livetv.ChannelGuideRow
import dev.kobbler.app.ui.dashboard.livetv.GuideFocus
import dev.kobbler.app.ui.dashboard.livetv.GuideSlot
import dev.kobbler.app.ui.dashboard.livetv.LiveFacetChips
import dev.kobbler.app.ui.dashboard.livetv.QuickPlayCard
import dev.kobbler.app.ui.dashboard.livetv.QuickPlayStrip
import dev.kobbler.app.ui.dashboard.livetv.RowIconButton
import dev.kobbler.app.ui.dashboard.livetv.localHhMm
import dev.kobbler.app.ui.dashboard.livetv.parseLiveInstant
import dev.kobbler.app.ui.dashboard.livetv.rememberGuideClock
import dev.kobbler.app.data.LiveFacet
import dev.kobbler.app.ui.imePaddingUnlessTv
import dev.kobbler.app.ui.scrollsUnderTopChrome
import dev.kobbler.kobfocus.LocalFocusScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private const val PAGE_SIZE = 50
private const val FavouriteFilter = "favourite"
// A 1080p TV is 540dp tall; below this the header drops to its compact title.
private val DenseIptvMaxHeight = 620.dp

private enum class IptvTab(val label: String) {
    Guide("Guide"), Channels("Channels"), Recordings("Recordings"),
}

private val IptvTabLabels = IptvTab.entries.map { it.label }

private data class IptvHeaderData(
    val favourites: List<IptvChannel>,
    val favouriteIds: Map<String, String>,
    val groups: List<IptvGroup>,
    val watchMatches: List<IptvWatchMatch>,
)

/**
 * The Live TV page's shape over a library of tens of thousands of channels: it opens on the
 * favourites, which are the only rows the server describes with a schedule, and pages the
 * rest in behind the All and group chips.
 */
@Composable
fun IptvScreen(
    api: KobblerApi,
    modifier: Modifier = Modifier,
    formFactor: FormFactor = FormFactor.Desktop,
    onChannelClick: (IptvChannel) -> Unit = {},
    onWatch: (IptvChannel) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val focusScope = LocalFocusScope.current
    var tabIdx by remember { mutableIntStateOf(IptvTab.Guide.ordinal) }

    val (headerState, headerRetry) = rememberCached("iptv-header") {
        coroutineScope {
            val favsDeferred = async { runCatching { api.getIptvEpgFavourites() }.getOrDefault(IptvEpgFavouritesResponse()) }
            // The schedule feed names the favourite channels but not the favourites, and
            // removing one needs its own id.
            val idsDeferred = async { runCatching { api.getIptvFavourites() }.getOrDefault(emptyList()) }
            val groupsDeferred = async { api.getIptvGroups() }
            val matchesDeferred = async { runCatching { api.getIptvWatchMatches() }.getOrDefault(emptyList()) }

            val favouriteIds = idsDeferred.await().associate { it.channelId to it.id }
            val favourites = favsDeferred.await().grid.map { fav ->
                val programmes = fav.programs.map { IptvEpgEntry(it.id, it.title, it.description, it.startTime, it.stopTime, it.categories) }
                IptvChannel(
                    id = fav.channel.id,
                    name = fav.channel.name,
                    iconUrl = fav.channel.iconUrl,
                    category = fav.channel.category,
                    language = null, country = null,
                    isFavourite = true,
                    favouriteId = favouriteIds[fav.channel.id],
                    nowPlaying = programmes.firstOrNull(),
                    nextUp = programmes.getOrNull(1),
                )
            }
            val matches = matchesDeferred.await()
                .map {
                    IptvWatchMatch(
                        watchId = it.watchId, channelId = it.channelId,
                        channelName = it.channelName, channelIconUrl = it.channelIconUrl,
                        epgTitle = it.epgTitle, startTime = it.startTime, stopTime = it.stopTime,
                        isLive = it.isLive,
                    )
                }
                .distinctBy { Triple(it.watchId, it.channelId, it.startTime) }
            IptvHeaderData(favourites, favouriteIds, groupsDeferred.await(), matches)
        }
    }

    AsyncContent(headerState, skeleton = { ListSkeleton() }, onRetry = headerRetry, modifier = modifier) { data ->
      BoxWithConstraints(Modifier.fillMaxSize()) {
        val dense = maxHeight < DenseIptvMaxHeight
        val colors = KobappTheme.colors
        val dimens = KobappTheme.dimens
        val accent = colors.accIptv
        val compact = formFactor == FormFactor.Phone
        val gutter = when (formFactor) {
            FormFactor.TV -> dimens.pageGutterTv
            FormFactor.Phone -> dimens.pageGutterPhone
            else -> dimens.pageGutter
        }
        val clock = rememberGuideClock(active = tabIdx == IptvTab.Guide.ordinal)

        // Until the viewer picks a chip: the favourites if there are any, since a page that
        // opens on channel one of twenty-six thousand has opened on nothing in particular.
        var picked by remember { mutableStateOf<String?>(null) }
        var hasPicked by remember { mutableStateOf(false) }
        val filter = if (hasPicked) picked else FavouriteFilter.takeIf { data.favourites.isNotEmpty() }

        var channels by remember { mutableStateOf<List<IptvChannel>>(emptyList()) }
        var channelTotal by remember { mutableIntStateOf(0) }
        var channelsLoading by remember { mutableStateOf(false) }
        LaunchedEffect(filter) {
            channels = emptyList()
            channelTotal = 0
            if (filter == FavouriteFilter) return@LaunchedEffect
            channelsLoading = true
            runCatching { api.getIptvChannels(category = filter, limit = PAGE_SIZE) }.onSuccess {
                channels = it.channels
                channelTotal = it.total
            }
            channelsLoading = false
        }
        fun loadMore() {
            if (filter == FavouriteFilter || channelsLoading || channels.size >= channelTotal) return
            scope.launch {
                channelsLoading = true
                runCatching { api.getIptvChannels(category = filter, limit = PAGE_SIZE, offset = channels.size) }.onSuccess {
                    channels = channels + it.channels
                    channelTotal = it.total
                }
                channelsLoading = false
            }
        }

        val rows = if (filter == FavouriteFilter) data.favourites else channels.map { channel ->
            data.favouriteIds[channel.id]?.let { channel.copy(isFavourite = true, favouriteId = it) } ?: channel
        }
        val allCount = maxOf(data.groups.sumOf { it.channelCount }.toInt(), channelTotal)

        var wantEntryFocus by remember { mutableStateOf(false) }
        LaunchedEffect(tabIdx) { wantEntryFocus = true }
        val entryKey = rows.firstOrNull()?.let { iptvRowKey(it.id) }.takeIf { tabIdx == IptvTab.Guide.ordinal }
        LaunchedEffect(entryKey, wantEntryFocus) {
            if (entryKey == null) return@LaunchedEffect
            if (!wantEntryFocus && focusScope.save() != null) return@LaunchedEffect
            // The rows are lazy and register when they are measured, a frame after the list
            // they belong to is composed; asked any sooner, entry falls through to the tabs.
            withFrameNanos { }
            focusScope.focusFirst()
            wantEntryFocus = false
        }

        val toggleFavourite: (IptvChannel) -> Unit = { channel ->
            scope.launch {
                runCatching {
                    val id = channel.favouriteId
                    if (channel.isFavourite && id != null) api.removeIptvFavourite(id) else api.addIptvFavourite(channel.id)
                }
                headerRetry()
            }
        }

        val tabs: @Composable (Modifier) -> Unit = { tabsModifier ->
            SegmentedFilter(
                options = IptvTabLabels,
                selectedIndex = tabIdx,
                accent = accent,
                fillWidth = compact,
                modifier = tabsModifier,
                onSelected = { tabIdx = it },
            )
        }

        PinnedHeader(
            header = {
                Column(
                    Modifier
                        .zIndex(1f)
                        .padding(horizontal = gutter)
                        .padding(top = if (dense || compact) dimens.s2 else dimens.s4),
                ) {
                    if (compact) {
                        tabs(Modifier.fillMaxWidth())
                    } else {
                        BrowseHeader(title = "IPTV", accent = accent, compact = dense, trailing = { tabs(Modifier) })
                    }
                }
            },
            modifier = Modifier.fillMaxSize().imePaddingUnlessTv(),
        ) {
            when (IptvTab.entries[tabIdx]) {
                IptvTab.Channels -> IptvChannelsDirectoryScreen(
                    api = api,
                    formFactor = formFactor,
                    onChannelClick = { id ->
                        onChannelClick(IptvChannel(id = id, name = "", iconUrl = null, category = null, language = null, country = null))
                    },
                    showHeader = false,
                )
                IptvTab.Recordings -> IptvRecordingsScreen(api = api, formFactor = formFactor, showHeader = false)
                IptvTab.Guide -> IptvGuideList(
                    rows = rows,
                    matches = data.watchMatches,
                    loading = channelsLoading,
                    hasMore = filter != FavouriteFilter && channels.size < channelTotal,
                    wide = !compact,
                    accent = accent,
                    gutter = gutter,
                    entryKey = entryKey,
                    clock = clock,
                    chips = {
                        LiveFacetChips(
                            groupKey = "iptvfacets",
                            facets = data.groups.map { LiveFacet(it.name, it.name, it.channelCount.toInt()) },
                            selected = filter,
                            accent = accent,
                            leading = listOfNotNull(
                                ("Favourites ${data.favourites.size}" to FavouriteFilter).takeIf { data.favourites.isNotEmpty() },
                                "All $allCount" to null,
                            ),
                            onSelect = { picked = it; hasPicked = true },
                        )
                    },
                    onLoadMore = ::loadMore,
                    onWatch = onWatch,
                    onOpenChannel = onChannelClick,
                    onToggleFavourite = toggleFavourite,
                    onMatch = { match ->
                        val channel = IptvChannel(
                            id = match.channelId, name = match.channelName, iconUrl = match.channelIconUrl,
                            category = null, language = null, country = null,
                        )
                        if (match.isLive) onWatch(channel) else onChannelClick(channel)
                    },
                )
            }
        }
      }
    }
}

private fun iptvRowKey(channelId: String) = "irow.$channelId"

@Composable
private fun IptvGuideList(
    rows: List<IptvChannel>,
    matches: List<IptvWatchMatch>,
    loading: Boolean,
    hasMore: Boolean,
    wide: Boolean,
    accent: Color,
    gutter: Dp,
    entryKey: String?,
    clock: dev.kobbler.app.ui.dashboard.livetv.GuideClock,
    chips: @Composable () -> Unit,
    onLoadMore: () -> Unit,
    onWatch: (IptvChannel) -> Unit,
    onOpenChannel: (IptvChannel) -> Unit,
    onToggleFavourite: (IptvChannel) -> Unit,
    onMatch: (IptvWatchMatch) -> Unit,
) {
    val colors = KobappTheme.colors
    val dimens = KobappTheme.dimens
    val listState = rememberLazyListState()
    val focus = remember { GuideFocus() }
    CenterFocusedRow(listState, focus, topRow = 0)

    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 5
        }
    }
    LaunchedEffect(nearEnd, hasMore) { if (nearEnd && hasMore) onLoadMore() }

    PinnedHeader(
        header = { Box(Modifier.padding(horizontal = gutter).padding(top = dimens.s2, bottom = dimens.s3)) { chips() } },
        modifier = Modifier.fillMaxSize(),
    ) {
        if (rows.isEmpty() && matches.isEmpty()) {
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = accent, modifier = Modifier.size(28.dp))
                }
            } else {
                EmptyState(icon = Icons.Outlined.LiveTv, title = "No channels", subtitle = "No IPTV channels configured")
            }
            return@PinnedHeader
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.scrollsUnderTopChrome(listState).fillMaxSize(),
            contentPadding = PaddingValues(top = LocalTopChromeOverlap.current, bottom = dimens.s6),
            verticalArrangement = Arrangement.spacedBy(if (wide) dimens.s1 else 2.dp),
        ) {
            if (matches.isNotEmpty()) {
                item(key = "matches") {
                    QuickPlayStrip("Matches", gutter, Modifier.padding(bottom = dimens.s3)) {
                        items(matches, key = { "${it.watchId}.${it.channelId}.${it.startTime}" }) { match ->
                            QuickPlayCard(
                                focusKey = "match.${match.watchId}.${match.channelId}.${match.startTime}",
                                logo = { size -> ChannelIcon(match.channelIconUrl, match.channelName, accent, size) },
                                title = match.epgTitle,
                                channelName = match.channelName,
                                timing = if (match.isLive) "LIVE" else parseLiveInstant(match.startTime)?.localHhMm(),
                                upcoming = !match.isLive,
                                accent = accent,
                                onClick = { onMatch(match) },
                            )
                        }
                    }
                }
            }
            val offset = if (matches.isNotEmpty()) 1 else 0
            itemsIndexed(rows, key = { _, it -> it.id }) { index, channel ->
                ChannelGuideRow(
                    name = channel.name,
                    logo = { size -> ChannelIcon(channel.iconUrl, channel.name, accent, size) },
                    current = channel.nowPlaying?.slot(),
                    following = listOfNotNull(channel.nextUp?.slot()),
                    showProgress = true,
                    clock = clock,
                    wide = wide,
                    accent = accent,
                    groupKey = "ich.${channel.id}",
                    rowKey = iptvRowKey(channel.id),
                    preferredEntry = iptvRowKey(channel.id) == entryKey,
                    emptyLabel = listOfNotNull(channel.category, channel.language?.uppercase()).joinToString(" · ").ifEmpty { "No guide" },
                    onClick = { onWatch(channel) },
                    modifier = Modifier
                        .padding(horizontal = gutter)
                        .onFocusChanged { if (it.hasFocus) focus.lazyIndex = index + offset },
                ) {
                    RowIconButton(
                        icon = if (channel.isFavourite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                        label = "${if (channel.isFavourite) "Remove favourite" else "Add favourite"} ${channel.name}",
                        focusKey = "favourite",
                        wide = wide,
                        onClick = { onToggleFavourite(channel) },
                        tint = if (channel.isFavourite) accent else colors.textLo,
                        iconSize = 18.dp,
                    )
                    RowIconButton(
                        icon = Icons.Outlined.CalendarMonth,
                        label = "${channel.name} schedule",
                        focusKey = "schedule",
                        wide = wide,
                        onClick = { onOpenChannel(channel) },
                        iconSize = 16.dp,
                    )
                }
            }
            if (loading) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = accent, modifier = Modifier.size(28.dp))
                    }
                }
            }
        }
    }
}

private fun IptvEpgEntry.slot(): GuideSlot? =
    title?.takeIf { it.isNotBlank() }?.let { GuideSlot(it, parseLiveInstant(startTime), parseLiveInstant(stopTime)) }

@Composable
internal fun ChannelIcon(iconUrl: String?, name: String, accent: Color, size: Dp) {
    val colors = KobappTheme.colors
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp))
            .background(Brush.linearGradient(listOf(lerp(colors.elev2, accent, 0.18f), colors.elev3)))
            .border(1.dp, colors.lineSoft, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (iconUrl != null) {
            AsyncImage(
                model = iconUrl,
                contentDescription = name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(4.dp),
            )
        } else {
            Icon(Icons.Outlined.LiveTv, contentDescription = null, tint = accent, modifier = Modifier.size(size * 0.5f))
        }
    }
}
