package dev.kobbler.app.ui.dashboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.kobbler.app.data.DiscoverSearchResults
import dev.kobbler.app.data.IptvChannel
import dev.kobbler.app.data.SearchResponse
import dev.kobbler.app.data.SearchSuggestion

/**
 * What the search page shows, kept by whoever outlives it. Opening a result takes the page
 * out of composition, and BACK has to put the viewer on the same results, at the same
 * place, without fetching them again.
 */
class SearchSession {
    internal var library by mutableStateOf<SearchResponse?>(null)
    internal var discover by mutableStateOf<DiscoverSearchResults?>(null)
    internal var live by mutableStateOf<LiveSearchResults?>(null)
    internal var iptv by mutableStateOf<List<IptvChannel>?>(null)
    internal val expanded = mutableStateMapOf<String, Boolean>()
    internal var suggestions by mutableStateOf<List<SearchSuggestion>>(emptyList())
    internal var suggestionsKey: Pair<String, SearchScope>? = null
    internal var resultsKey: SearchKey? = null
    internal var completeKey: SearchKey? = null
    private var pendingReturn: SearchReturn? = null

    internal fun leaveFor(focusKey: String?, firstVisibleIndex: Int, firstVisibleOffset: Int) {
        pendingReturn = resultsKey?.let { SearchReturn(it, focusKey, firstVisibleIndex, firstVisibleOffset) }
    }

    internal fun takeReturn(key: SearchKey): SearchReturn? =
        pendingReturn.also { pendingReturn = null }?.takeIf { it.key == key }

    fun reset() {
        pendingReturn = null
        clear()
    }

    internal fun clear() {
        library = null
        discover = null
        live = null
        iptv = null
        resultsKey = null
        completeKey = null
    }
}

internal data class SearchKey(val query: String, val scope: SearchScope, val live: Boolean, val iptv: Boolean)

internal data class SearchReturn(val key: SearchKey, val focusKey: String?, val index: Int, val offset: Int)
