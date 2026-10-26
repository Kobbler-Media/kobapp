package dev.kobbler.app.ui.dashboard

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.SearchSuggestion
import dev.kobbler.app.ui.components.KobChip
import dev.kobbler.app.ui.components.KobChipStyle
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup

suspend fun fetchSuggestions(api: KobblerApi, query: String, scope: SearchScope): List<SearchSuggestion> =
    runCatching { api.searchSuggestions(query.trim(), scope.suggestionMediaTypes) }.getOrDefault(emptyList())

@Composable
internal fun SearchSuggestionChips(suggestions: List<SearchSuggestion>, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.focusKeyGroup("searchsuggest").horizontalScroll(rememberScrollState()),
    ) {
        suggestions.forEach { suggestion ->
            KobChip(
                text = suggestion.title,
                style = KobChipStyle.Ghost,
                leading = { Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(14.dp)) },
                onClick = { onPick(suggestion.title) },
                modifier = Modifier.focusKey("suggest.${suggestion.title}", entryEligible = false),
            )
        }
    }
}

internal fun LazyGridScope.trendingSuggestions(suggestions: List<SearchSuggestion>, onPick: (String) -> Unit) {
    if (suggestions.isEmpty()) return
    fullWidthItem(key = "trending-head") { SearchTermsHeader("TRENDING", Icons.AutoMirrored.Outlined.TrendingUp) }
    items(suggestions, key = { "trending-${it.title}" }, span = { GridItemSpan(maxLineSpan) }) { suggestion ->
        SearchTermRow(
            term = suggestion.title,
            icon = Icons.AutoMirrored.Outlined.TrendingUp,
            focusKey = "trending.${suggestion.title}",
            onClick = { onPick(suggestion.title) },
        )
    }
}
