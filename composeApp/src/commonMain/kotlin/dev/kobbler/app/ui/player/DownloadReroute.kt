package dev.kobbler.app.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.offline.Offline
import dev.kobbler.app.offline.OfflineItem
import dev.kobbler.app.offline.OfflineKind
import dev.kobbler.app.offline.OfflineState
import dev.kobbler.kobplayer.Player
import dev.kobbler.kobplayer.PlayerState
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Moves the title on screen onto its download as soon as it has one, however the download was
 * started, so what plays from then on is kept: the stream it opened with went straight to the
 * server, and nothing read from it was written down.
 */
@Composable
internal fun RouteDownloadsThroughProxy(gps: GlobalPlayerState, player: Player, api: KobblerApi) {
    val engine = Offline.engine ?: return
    LaunchedEffect(gps, player, api) {
        combine(engine.store.index, snapshotFlow { Triple(gps.mediaType, gps.mediaId, gps.lastStreamUrl) }) { index, (type, id, url) ->
            val kind = when (type) {
                "movie" -> OfflineKind.Movie
                "episode" -> OfflineKind.Episode
                else -> return@combine null
            }
            if (id == null || url == null || engine.servesLocally(url) || gps.isDebrid) return@combine null
            val item = index.items[OfflineItem.keyOf(kind, id)] ?: return@combine null
            if (item.state == OfflineState.Failed || !item.playsLocally) null else type to id
        }.distinctUntilChanged().collect { target ->
            if (target != null) gps.reopenThroughDownload(player, api, target.first, target.second)
        }
    }
}

private suspend fun GlobalPlayerState.reopenThroughDownload(player: Player, api: KobblerApi, type: String, id: String) {
    val previous = sessionId
    val keepPaused = player.state.value == PlayerState.PAUSED
    val resumeSec = (timeline.toAbsoluteMs(player.currentMs.value) / 1000).toInt()
    val audioTitle = rememberedAudioTitle(playbackTitleKey)
    val session = (if (type == "movie") api.getMovieStreamSession(id, audioTitle = audioTitle) else api.getEpisodeStreamSession(id, audioTitle = audioTitle)) ?: return
    if (mediaId != id) return
    loadInPlace(player, api, type, id, session, resumeSec)
    if (keepPaused) endPreload(player) else playUnmuted(player)
    if (previous != null && previous != session.sessionId) teardownScope.launch { api.closeSession(previous) }
}
