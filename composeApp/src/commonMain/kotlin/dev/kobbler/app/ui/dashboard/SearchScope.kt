package dev.kobbler.app.ui.dashboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.SearchHitDto
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.KobChip
import dev.kobbler.app.ui.components.KobChipStyle
import dev.kobbler.app.ui.detail.OverflowItem
import dev.kobbler.app.ui.detail.OverflowMenu
import dev.kobbler.app.ui.navigation.Destination
import dev.kobbler.kobfocus.focusKey

enum class SearchScope(val label: String, val placeholder: String) {
    All("All", "Search movies, TV, books, authors…"),
    Movies("Movies", "Search movies…"),
    Tv("TV", "Search TV shows…"),
    Sports("Sports", "Search sports…"),
    Books("Books", "Search books and authors…"),
    LiveTv("Live TV", "Search channels and the TV guide…"),
    Iptv("IPTV", "Search IPTV channels…"),
    ;

    fun includes(hit: SearchHitDto): Boolean = when (this) {
        All -> hit.type in TvTypes || hit.type in BookTypes || hit.type == "movie" || hit.type == "person"
        Movies -> hit.type == "movie" || hit.type == "person"
        Tv -> (hit.sectionType in TvTypes) || hit.type == "person"
        Sports -> hit.sectionType == "sport"
        Books -> hit.type in BookTypes
        LiveTv, Iptv -> false
    }

    val discoverMediaTypes: Set<String>
        get() = when (this) {
            All -> setOf("movie", "tv")
            Movies -> setOf("movie")
            Tv -> setOf("tv")
            Books -> setOf("book")
            Sports, LiveTv, Iptv -> emptySet()
        }

    val suggestionMediaTypes: Set<String>
        get() = when (this) {
            All -> setOf("movie", "tv", "book")
            Movies -> setOf("movie")
            Tv -> setOf("tv")
            Sports -> setOf("sports")
            Books -> setOf("book")
            LiveTv -> setOf("livetv")
            Iptv -> setOf("iptv")
        }

    val searchesLibrary: Boolean get() = this != LiveTv && this != Iptv

    companion object {
        fun forTab(tab: Destination.Tab): SearchScope = when (tab) {
            Destination.Tab.Movies -> Movies
            Destination.Tab.TvShows -> Tv
            Destination.Tab.Sports -> Sports
            Destination.Tab.Books, Destination.Tab.Audiobooks -> Books
            Destination.Tab.LiveTv -> LiveTv
            Destination.Tab.Iptv -> Iptv
            else -> All
        }
    }
}

private val TvTypes = setOf("tv_show", "tv_season", "tv_episode")
private val BookTypes = setOf("book", "author", "series")

internal const val LiveChannelResultType = "live_channel"
internal const val IptvChannelResultType = "iptv_channel"
internal const val SearchScopeChipTag = "search-scope"

@Composable
internal fun SearchScopeChip(
    scope: SearchScope,
    scopes: List<SearchScope>,
    onSelect: (SearchScope) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val anchor = remember { FocusRequester() }
    val accent = KobappTheme.accent
    Box {
        KobChip(
            text = scope.label,
            style = if (scope == SearchScope.All) KobChipStyle.Default else KobChipStyle.AccentTint,
            leading = { Icon(Icons.Outlined.FilterList, contentDescription = null, modifier = Modifier.size(14.dp)) },
            onClick = { open = true },
            modifier = Modifier
                .focusKey("scope", entryEligible = false)
                .focusRequester(anchor)
                .testTag(SearchScopeChipTag),
        )
        OverflowMenu(expanded = open, anchor = anchor, onClose = { open = false }) { close ->
            scopes.forEach { option ->
                OverflowItem(
                    key = "scope.${option.name}",
                    icon = option.icon,
                    label = option.label,
                    accent = accent,
                    selected = option == scope,
                    onClick = {
                        onSelect(option)
                        close()
                    },
                )
            }
        }
    }
}

private val SearchScope.icon: ImageVector
    get() = when (this) {
        SearchScope.All -> Icons.Outlined.Search
        SearchScope.Movies -> Destination.Tab.Movies.icon
        SearchScope.Tv -> Destination.Tab.TvShows.icon
        SearchScope.Sports -> Destination.Tab.Sports.icon
        SearchScope.Books -> Destination.Tab.Books.icon
        SearchScope.LiveTv -> Destination.Tab.LiveTv.icon
        SearchScope.Iptv -> Destination.Tab.Iptv.icon
    }
