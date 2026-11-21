package dev.kobbler.app.ui.dashboard.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import dev.kobbler.app.data.DiscoverItem
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.LibraryStatus
import dev.kobbler.app.ui.components.CardAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class DiscoverCard(val status: LibraryStatus, val menu: List<CardAction>)

/** [scope] outlives the card, so a want sent just before the card scrolls away still lands. */
@Composable
internal fun rememberDiscoverCard(
    item: DiscoverItem,
    api: KobblerApi,
    scope: CoroutineScope,
    onClick: (DiscoverItem) -> Unit,
    more: List<CardAction> = emptyList(),
): DiscoverCard {
    // Home's rails are not refetched after a want, so the card is the only thing that can answer it.
    var wanted by rememberSaveable(item.mediaType, item.provider, item.providerId) { mutableStateOf(false) }
    val status = if (wanted && item.libraryStatus == LibraryStatus.NotInLibrary) LibraryStatus.Wanted else item.libraryStatus
    // buildList hands back a fresh List every call, and List compares by reference under
    // strong skipping — unremembered, no card in any rail could ever skip.
    val menu = remember(item, api, status, more) {
        buildList {
            add(CardAction("Info", { onClick(item) }))
            if (status == LibraryStatus.NotInLibrary && api.canWant) {
                add(CardAction("Want", {
                    wanted = true
                    scope.launch { runCatching { want(api, item) }.onFailure { wanted = false } }
                }))
            }
            listAdd(api, item)?.let { add(CardAction("Add to my list", { scope.launch { runCatching { it() } } }, Icons.AutoMirrored.Outlined.PlaylistAdd)) }
            addAll(more)
        }
    }
    return DiscoverCard(status, menu)
}

/** Only a contributor may create the library row a list entry needs; anyone may list a title already there. */
private fun listAdd(api: KobblerApi, item: DiscoverItem): (suspend () -> Unit)? {
    val kind = when (item.mediaType) {
        "tv" -> "show"
        "movie" -> "movie"
        else -> return null
    }
    if (api.canWant) return { api.addDiscoverToWatchlist(api.getDiscoverDetail(item.mediaType, item.provider, item.providerId)) }
    return item.libraryId?.let { id -> { api.addToWatchlist(kind, id) } }
}

private suspend fun want(api: KobblerApi, item: DiscoverItem) {
    val detail = api.getDiscoverDetail(item.mediaType, item.provider, item.providerId)
    when (item.mediaType) {
        "movie" -> api.wantMovie(detail)
        "tv" -> api.wantTvShow(detail)
        "book" -> api.wantBook(detail)
    }
}
