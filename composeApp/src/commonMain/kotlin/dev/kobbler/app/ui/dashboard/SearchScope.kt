package dev.kobbler.app.ui.dashboard

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
        All -> libraryType in TvTypes || libraryType in BookTypes || libraryType == "movie" || libraryType == "person"
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

/** A filter, not a radio button: tapping the one already on clears it back to All. */
fun SearchScope.tapped(option: SearchScope): SearchScope =
    if (option == this && option != SearchScope.All) SearchScope.All else option

@Composable
internal fun SearchScopeChips(
    scope: SearchScope,
    scopes: List<SearchScope>,
    onSelect: (SearchScope) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .focusKeyGroup("searchscope")
            .horizontalScroll(rememberScrollState()),
    ) {
        scopes.forEach { option ->
            KobChip(
                text = option.label,
                style = if (option == scope) KobChipStyle.AccentTint else KobChipStyle.Default,
                onClick = { onSelect(scope.tapped(option)) },
                modifier = Modifier.focusKey("scope.${option.name}", entryEligible = false),
            )
        }
    }
}
