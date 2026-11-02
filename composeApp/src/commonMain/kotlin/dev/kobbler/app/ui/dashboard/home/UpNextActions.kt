package dev.kobbler.app.ui.dashboard.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.MediaType
import dev.kobbler.app.data.UpNextItem
import dev.kobbler.app.ui.components.CardAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun rememberUpNextActions(item: UpNextItem, api: KobblerApi): List<CardAction> {
    val scope = rememberCoroutineScope()
    return remember(item, api) { upNextActions(item, api, scope) }
}

private fun upNextActions(item: UpNextItem, api: KobblerApi, scope: CoroutineScope): List<CardAction> {
    fun action(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, call: suspend () -> Unit) =
        CardAction(label, { scope.launch { runCatching { call() } } }, icon)
    return when (item.mediaType) {
        MediaType.Movie -> listOf(action("Mark watched", Icons.Outlined.Check) { api.setMovieWatched(item.mediaId, true) })
        MediaType.TvShow, MediaType.SportsEvent -> listOf(
            action("Mark watched", Icons.Outlined.Check) { api.setEpisodeWatched(item.mediaId, true) },
            action("Mark earlier episodes watched", Icons.Outlined.DoneAll) { api.markEarlierEpisodesWatched(item.mediaId) },
        )
        MediaType.Book, MediaType.Audiobook -> emptyList()
    }
}
