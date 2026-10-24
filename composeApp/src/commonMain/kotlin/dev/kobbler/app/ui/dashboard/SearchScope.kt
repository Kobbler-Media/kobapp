package dev.kobbler.app.ui.dashboard

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kobbler.app.ui.components.KobChip
import dev.kobbler.app.ui.components.KobChipStyle
import dev.kobbler.app.ui.navigation.Destination
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup

enum class SearchScope(val label: String, val placeholder: String) {
    All("All", "Search movies, TV, books, authors…"),
    Movies("Movies", "Search movies…"),
    Tv("TV", "Search TV shows…"),
    Books("Books", "Search books and authors…"),
    LiveTv("Live TV", "Search channels and the TV guide…"),
    Iptv("IPTV", "Search IPTV channels…"),
    ;

    fun includes(libraryType: String): Boolean = when (this) {
        All -> true
        Movies -> libraryType == "movie" || libraryType == "person"
        Tv -> libraryType in TvTypes || libraryType == "person"
        Books -> libraryType in BookTypes
        LiveTv, Iptv -> false
    }

    val discoverMediaTypes: Set<String>
        get() = when (this) {
            All -> setOf("movie", "tv")
            Movies -> setOf("movie")
            Tv -> setOf("tv")
            Books -> setOf("book")
            LiveTv, Iptv -> emptySet()
        }

    val searchesLibrary: Boolean get() = this != LiveTv && this != Iptv

    companion object {
        fun forTab(tab: Destination.Tab): SearchScope = when (tab) {
            Destination.Tab.Movies -> Movies
            Destination.Tab.TvShows -> Tv
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

@Composable
internal fun SearchScopeChips(scope: SearchScope, scopes: List<SearchScope>, onSelect: (SearchScope) -> Unit, phone: Boolean) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .focusKeyGroup("searchscope")
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = if (phone) 16.dp else 18.dp)
            .padding(bottom = 6.dp),
    ) {
        scopes.forEach { option ->
            KobChip(
                text = option.label,
                style = if (option == scope) KobChipStyle.AccentTint else KobChipStyle.Default,
                onClick = { onSelect(option) },
                modifier = Modifier.focusKey("scope.${option.name}", entryEligible = false),
            )
        }
    }
}
