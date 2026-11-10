package dev.kobbler.app.ui.dashboard

import androidx.compose.foundation.layout.*
import dev.kobbler.app.ui.scrollsUnderTopChrome
import dev.kobbler.app.ui.LocalTopChromeOverlap
import dev.kobbler.app.ui.components.GridSkeleton
import dev.kobbler.app.ui.PinnedHeader
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.WantedItem
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.*

enum class WantedTab(val label: String) {
    Downloads("Downloads"),
    Wanted("Wanted"),
}

private enum class WantedFilter(val label: String, val mediaType: String?) {
    All("All", null),
    Movies("Movies", "movie"),
    TV("TV", "tv_show"),
    Books("Books", "book"),
}

/**
 * Wanted and Downloads share one route and one full-width page; the header's segmented
 * control swaps between them. Two panes on a 10-foot screen left both unreadable.
 */
@Composable
fun WantedScreen(
    api: KobblerApi,
    modifier: Modifier = Modifier,
    formFactor: FormFactor = FormFactor.Desktop,
    initialTab: WantedTab = WantedTab.Downloads,
    onItemClick: (WantedItem) -> Unit = {},
    onDownloadClick: (DownloadTarget) -> Unit = {},
) {
    var tab by rememberSaveable(initialTab) { mutableStateOf(initialTab) }
    val colors = KobappTheme.colors
    val tabSwitch: @Composable () -> Unit = {
        SegmentedFilter(
            options = WantedTab.entries.map { it.label },
            selectedIndex = tab.ordinal,
            accent = if (tab == WantedTab.Wanted) colors.accWanted else colors.accDown,
            modifier = Modifier.focusKeyGroup("tabs"),
            onSelected = { tab = WantedTab.entries[it] },
        )
    }

    when (tab) {
        WantedTab.Wanted -> WantedList(api, modifier, formFactor, tabSwitch, onItemClick)
        WantedTab.Downloads -> DownloadsScreen(api, modifier, formFactor, tabSwitch, onDownloadClick)
    }
}

@Composable
private fun WantedList(
    api: KobblerApi,
    modifier: Modifier,
    formFactor: FormFactor,
    tabSwitch: @Composable () -> Unit,
    onItemClick: (WantedItem) -> Unit,
) {
    val (state, retry) = rememberCached("wanted") { api.getWantedItems() }
    var selectedFilterIdx by rememberSaveable { mutableIntStateOf(0) }
    val colors = KobappTheme.colors
    val dimens = KobappTheme.dimens
    val compact = formFactor == FormFactor.Phone
    val gutter = when (formFactor) {
        FormFactor.TV -> dimens.pageGutterTv
        FormFactor.Phone -> dimens.pageGutterPhone
        else -> dimens.pageGutter
    }
    val loaded = (state as? Async.Ready)?.value.orEmpty()

    PinnedHeader(
        header = {
            Box(Modifier.padding(horizontal = gutter, vertical = dimens.s4)) {
                BrowseHeader(
                    title = "Wanted",
                    accent = colors.accWanted,
                    countLine = "${loaded.size} pending",
                    compact = compact,
                    trailing = tabSwitch,
                    secondaryRow = {
                        SegmentedFilter(
                            options = WantedFilter.entries.map { it.label },
                            selectedIndex = selectedFilterIdx,
                            accent = colors.accWanted,
                            onSelected = { selectedFilterIdx = it },
                        )
                    },
                )
            }
        },
        modifier = modifier.fillMaxSize(),
    ) {
        AsyncContent(state, skeleton = { GridSkeleton() }, onRetry = retry) { items ->
            if (items.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.Bookmark,
                    title = "No wanted items",
                    subtitle = "Items you want will appear here. Use Discover to find new content.",
                )
            } else {
                val filter = WantedFilter.entries[selectedFilterIdx]
                val filtered = remember(items, filter) {
                    if (filter.mediaType == null) items
                    else items.filter { it.mediaType == filter.mediaType }
                }
                val gridState = rememberLazyGridState()
                FocusEdgeSlack {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(minSize = dimens.posterWidth),
                        contentPadding = PaddingValues(start = gutter, end = gutter, top = dimens.s4 + LocalTopChromeOverlap.current, bottom = dimens.s6),
                        horizontalArrangement = Arrangement.spacedBy(dimens.s3),
                        verticalArrangement = Arrangement.spacedBy(dimens.s4),
                        modifier = Modifier.scrollsUnderTopChrome(gridState).fillMaxSize(),
                    ) {
                        items(filtered, key = { "${it.mediaType}-${it.id}" }) { item ->
                            val accent = when (item.mediaType) {
                                "movie" -> colors.accMovies
                                "tv_show" -> colors.accTv
                                else -> colors.accBooks
                            }
                            PosterCard(
                                title = item.title,
                                imageUrl = item.coverUrl,
                                subtitle = item.subtitle,
                                accentColor = accent,
                                onClick = { onItemClick(item) },
                                modifier = Modifier.focusKey("wanted.${item.mediaType}.${item.id}"),
                            )
                        }
                    }
                }
            }
        }
    }
}
