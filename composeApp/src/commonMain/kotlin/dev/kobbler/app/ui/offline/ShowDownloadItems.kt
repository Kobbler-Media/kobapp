package dev.kobbler.app.ui.offline

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import dev.kobbler.app.data.Season
import dev.kobbler.app.data.TvShow
import dev.kobbler.app.offline.OfflineIndex
import dev.kobbler.app.offline.OfflineItem
import dev.kobbler.app.offline.OfflineKind
import dev.kobbler.app.offline.downloadEpisodes
import dev.kobbler.app.ui.detail.OverflowItem
import dev.kobbler.app.ui.detail.actions.ShowDownloads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private fun OfflineIndex.has(episodeId: String?) = episodeId != null && OfflineItem.keyOf(OfflineKind.Episode, episodeId) in items

@Composable
internal fun rememberShowDownloads(show: TvShow): ShowDownloads? {
    LocalOfflineEngine.current ?: return null
    val index = rememberOfflineIndex() ?: return null
    val playable = show.seasons.flatMap { it.episodes }.filter { it.filePath != null }
    return ShowDownloads(allHeld = playable.all { index.has(it.id) }, following = show.id in index.subscriptions)
}

/** Season entries for the season strip's menu. */
@Composable
internal fun SeasonDownloadItems(show: TvShow, season: Season, accent: Color, scope: CoroutineScope, close: () -> Unit) {
    val engine = LocalOfflineEngine.current ?: return
    val index = rememberOfflineIndex() ?: return
    val playable = season.episodes.filter { it.filePath != null && it.id != null }
    val missing = playable.filterNot { index.has(it.id) }
    val held = playable.filter { index.has(it.id) }
    if (missing.isNotEmpty()) {
        OverflowItem("downloadSeason", Icons.Outlined.DownloadForOffline, "Save season ${season.number} offline", accent, onClick = {
            close()
            engine.downloadEpisodes(show, missing)
        })
    }
    if (held.isNotEmpty()) {
        OverflowItem("removeSeason", Icons.Outlined.Delete, "Remove season ${season.number} downloads", accent, onClick = {
            close()
            scope.launch { held.forEach { engine.remove(OfflineItem.keyOf(OfflineKind.Episode, it.id!!)) } }
        })
    }
}

@Composable
fun seasonHasDownloadActions(season: Season): Boolean =
    LocalOfflineEngine.current != null && season.episodes.any { it.filePath != null && it.id != null }

/** "3 episodes offline" for a show's meta line. */
@Composable
fun metaShowOffline(show: TvShow): (@Composable () -> Unit)? {
    val engine = LocalOfflineEngine.current ?: return null
    val complete by engine.completeKeys.collectAsState()
    val count = show.seasons.sumOf { s -> s.episodes.count { it.id != null && OfflineItem.keyOf(OfflineKind.Episode, it.id) in complete } }
    if (count == 0) return null
    return { OfflineBadge(label = if (count == 1) "1 episode offline" else "$count episodes offline") }
}
