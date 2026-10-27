package dev.kobbler.app.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.SearchSuggestion
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.TvFocusScale
import dev.kobbler.app.ui.components.tvAutoGlow
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup

internal const val SearchSuggestionStripTag = "search-suggestions"

/** Shows and movies lead: the app is mostly watched from, and the server's order is the web's, which reads too. */
suspend fun fetchSuggestions(api: KobblerApi, query: String, scope: SearchScope): List<SearchSuggestion> =
    runCatching { api.searchSuggestions(query.trim(), scope.suggestionMediaTypes) }.getOrDefault(emptyList())
        .sortedBy { it.mediaType != "movie" && it.mediaType != "tv" }

@Composable
internal fun SearchSuggestionStrip(suggestions: List<SearchSuggestion>, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = KobappTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .testTag(SearchSuggestionStripTag)
            .focusKeyGroup("searchsuggest")
            .background(colors.elev1)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        suggestions.forEachIndexed { index, suggestion ->
            if (index > 0) Box(Modifier.width(1.dp).height(18.dp).background(colors.lineSoft))
            Text(
                suggestion.title,
                style = KobappTheme.type.body,
                fontWeight = if (index == 0) FontWeight.SemiBold else FontWeight.Normal,
                color = if (index == 0) colors.textHi else colors.textMd,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .focusKey("suggest.${suggestion.title}", entryEligible = false)
                    .tvAutoGlow(cornerRadiusDp = 8f, scale = TvFocusScale.Subtle)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onPick(suggestion.title) }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .heightIn(min = 36.dp)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}
