package dev.kobbler.app.ui.player

import dev.kobbler.app.data.IptvChannel
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.kobplayer.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

fun startIptvWatch(
    channel: IptvChannel,
    player: Player?,
    gps: GlobalPlayerState,
    api: KobblerApi,
    scope: CoroutineScope,
) {
    if (player == null || gps.isPreparing) return

    gps.sessionId?.let { sid ->
        scope.launch { api.closeSession(sid) }
    }
    gps.sessionId = null
    gps.mediaType = "iptv"
    gps.mediaId = channel.id
    gps.title = channel.name
    gps.subtitle = channel.nowPlaying?.title
    gps.posterUrl = channel.iconUrl
    gps.logoUrl = null
    gps.detailDestination = null
    gps.chapters = emptyList()
    gps.skipSegments = emptyList()
    gps.thumbnailsUrl = null
    gps.lufs = null
    gps.serverAudioTracks = emptyList()
    gps.selectedServerAudioIndex = null
    gps.serverSubtitleTracks = emptyList()
    gps.selectedServerSubtitleTrackId = null
    gps.isLive = true
    gps.loadingError = null
    gps.isPreparing = true
    gps.preparingNote = "Probing live sources — this can take up to a minute."
    gps.isFullscreen = true

    scope.launch {
        try {
            val session = api.playIptvChannel(channel.id)
            if (gps.mediaId != channel.id || !gps.isPreparing) return@launch

            val relUrl = session.playlistUrl ?: session.directUrl
                ?: throw IllegalStateException("Server returned no stream URL (session_id=${session.sessionId})")
            val url = if (relUrl.startsWith("http://") || relUrl.startsWith("https://")) relUrl
                      else "${api.apiUrl}$relUrl"

            gps.load(
                player = player, api = api,
                type = "iptv", id = channel.id,
                streamUrl = url, newSessionId = session.sessionId,
                newTitle = channel.name, newSubtitle = channel.nowPlaying?.title,
                newPosterUrl = channel.iconUrl, newLogoUrl = null,
                destination = null,
            )
            gps.playUnmuted(player)
        } catch (e: Exception) {
            if (gps.mediaId == channel.id) {
                gps.failWith(e, listOf(
                    "Channel: ${channel.name}",
                    "ID: ${channel.id}",
                    "Endpoint: ${api.apiUrl}/api/v1/admin/iptv/sessions",
                ))
            }
        } finally {
            if (gps.mediaId == channel.id) {
                gps.isPreparing = false
                gps.preparingNote = null
            }
        }
    }
}
