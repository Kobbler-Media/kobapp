package dev.kobbler.app.ui.offline

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.kobbler.app.data.Season
import dev.kobbler.app.data.TvShow
import dev.kobbler.app.offline.OfflineIndex
import dev.kobbler.app.offline.OfflineItem
import dev.kobbler.app.offline.OfflineKind
import dev.kobbler.app.offline.downloadEpisodes
import dev.kobbler.app.offline.downloadShow
import dev.kobbler.app.offline.setAutoDownload
import dev.kobbler.app.ui.detail.OverflowItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private fun OfflineIndex.has(episodeId: String?) = episodeId != null && OfflineItem.keyOf(OfflineKind.Episode, episodeId) in items

/** Whole-show entries for the show's own menu; nothing where downloads are not supported. */
@Composable
internal fun ShowDownloadItems(show: TvShow, accent: Color, close: () -> Unit) {
    val engine = LocalOfflineEngine.current ?: return
    val index = rememberOfflineIndex() ?: return
    val playable = show.seasons.flatMap { it.episodes }.filter { it.filePath != null }
    if (playable.isEmpty()) return
    val following = show.id in index.subscriptions
    if (playable.any { !index.has(it.id) } || !following) {
        OverflowItem("downloadShow", Icons.Outlined.Download, "Download all episodes", accent, onClick = { close(); engine.downloadShow(show) })
    }
    OverflowItem(
        "autoDownload",
        Icons.Outlined.NewReleases,
        "Download new episodes",
        accent,
        onClick = { close(); engine.setAutoDownload(show, !following) },
        selected = following,
    )
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
        OverflowItem("downloadSeason", Icons.Outlined.Download, "Download season ${season.number}", accent, onClick = {
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
    val index = rememberOfflineIndex() ?: return null
    val ids = show.seasons.flatMap { it.episodes }.mapNotNull { it.id }.toSet()
    val count = index.items.values.count { it.kind == OfflineKind.Episode && it.mediaId in ids && it.state == dev.kobbler.app.offline.OfflineState.Complete }
    if (count == 0) return null
    return { OfflineBadge(label = if (count == 1) "1 episode offline" else "$count episodes offline") }
}
