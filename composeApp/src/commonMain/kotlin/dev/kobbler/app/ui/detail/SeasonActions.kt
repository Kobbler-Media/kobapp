package dev.kobbler.app.ui.detail

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.Season
import dev.kobbler.app.data.TvShow
import kotlinx.coroutines.launch

/**
 * What can be done to the selected season, opened from the strip that selects it. In the
 * show's own menu these sat beside whole-show actions and had to name a season the viewer
 * may not have realised was selected. Null when there is nothing to offer.
 */
@Composable
internal fun seasonMenu(
    show: TvShow,
    season: Season?,
    api: KobblerApi?,
    watchedOverrides: SnapshotStateMap<String, Boolean>,
    wantOverrides: SnapshotStateMap<String, Boolean>,
    toast: ToastState,
    accent: Color,
    fallbackFocus: FocusRequester? = null,
): (@Composable ColumnScope.(close: () -> Unit) -> Unit)? {
    // Owned out here: menu content is disposed on close, cancelling anything it launched.
    val scope = rememberCoroutineScope()
    if (api == null || season == null) return null
    val canMark = season.episodes.any { it.id != null && !it.isWatchedNow(watchedOverrides) }
    val canWant = season.id != null && season.episodes.any { it.canBeWanted(api.canWant) && !it.isWantedNow(wantOverrides) }
    if (!canMark && !canWant) return null

    return { close ->
        if (canMark) {
            OverflowItem(
                key = "markSeasonWatched",
                icon = Icons.Outlined.Check,
                label = "Mark season ${season.number} watched",
                accent = accent,
                onClick = {
                    close()
                    scope.launch {
                        try {
                            season.episodes.forEach { ep ->
                                if (ep.id != null && !ep.isWatchedNow(watchedOverrides)) {
                                    runCatching { api.setEpisodeWatched(ep.id, true) }
                                        .onSuccess { watchedOverrides[ep.id] = true }
                                }
                            }
                        } finally {
                            // Marking the last of it removes this menu, and its focus with it.
                            runCatching { fallbackFocus?.requestFocus() }
                        }
                    }
                },
            )
        }
        if (canWant && season.id != null) {
            OverflowItem(
                key = "wantSeason",
                icon = Icons.Outlined.BookmarkAdd,
                label = "Want season ${season.number}",
                accent = accent,
                onClick = {
                    close()
                    val missing = season.episodes.filter { it.id != null && it.canBeWanted(api.canWant) }
                    missing.forEach { wantOverrides[it.id!!] = true }
                    scope.launch {
                        runCatching { api.wantSeason(show.id, season.id) }
                            .onSuccess { toast.show("Season ${season.number} added to wanted") }
                            .onFailure {
                                missing.forEach { wantOverrides.remove(it.id!!) }
                                toast.show("Could not want that season")
                            }
                    }
                },
            )
        }
    }
}
