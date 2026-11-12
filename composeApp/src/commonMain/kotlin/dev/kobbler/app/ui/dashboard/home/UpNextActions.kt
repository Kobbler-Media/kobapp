package dev.kobbler.app.ui.dashboard.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.vector.ImageVector
import dev.kobbler.app.data.HomeData
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.MediaType
import dev.kobbler.app.data.UpNextItem
import dev.kobbler.app.data.toUpNextItem
import dev.kobbler.app.ui.components.CardAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class UpNextQueue(val items: List<UpNextItem>, val hide: (String) -> Unit)

/** Up Next as fetched, less whatever was just waved off here, until the next fetch has its say. */
@Composable
internal fun rememberUpNextQueue(homeData: HomeData): UpNextQueue {
    val hidden = remember(homeData) { mutableStateSetOf<String>() }
    val all = remember(homeData) { homeData.watchNext.map { it.toUpNextItem() } }
    val shown by remember(all, hidden) { derivedStateOf { all.filterNot { it.mediaId in hidden } } }
    return remember(shown, hidden) { UpNextQueue(shown) { hidden += it } }
}

@Composable
internal fun rememberUpNextActions(item: UpNextItem, api: KobblerApi, onHide: (String) -> Unit): List<CardAction> {
    val scope = rememberCoroutineScope()
    return remember(item, api, onHide) { upNextActions(item, api, scope, onHide) }
}

private fun upNextActions(item: UpNextItem, api: KobblerApi, scope: CoroutineScope, onHide: (String) -> Unit): List<CardAction> {
    fun action(label: String, icon: ImageVector, hides: Boolean = false, call: suspend () -> Unit) =
        CardAction(label, {
            if (hides) onHide(item.mediaId)
            scope.launch { runCatching { call() } }
        }, icon)
    val notInterested = action("Not interested", Icons.Outlined.VisibilityOff, hides = true) { api.dismissUpNext(item.mediaType, item.mediaId) }
    return when (item.mediaType) {
        MediaType.Movie -> listOf(
            action("Mark watched", Icons.Outlined.Check, hides = true) { api.setMovieWatched(item.mediaId, true) },
            notInterested,
        )
        MediaType.TvShow, MediaType.SportsEvent -> listOf(
            action("Mark watched", Icons.Outlined.Check) { api.setEpisodeWatched(item.mediaId, true) },
            action("Mark earlier episodes watched", Icons.Outlined.DoneAll) { api.markEarlierEpisodesWatched(item.mediaId) },
            notInterested,
        )
        MediaType.Book, MediaType.Audiobook -> emptyList()
    }
}
