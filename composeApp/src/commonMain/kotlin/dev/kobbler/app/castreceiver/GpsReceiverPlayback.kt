package dev.kobbler.app.castreceiver

import dev.kobbler.app.data.CastSkip
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.ui.player.GlobalPlayerState
import dev.kobbler.app.ui.player.isVisibleSkipSegment
import dev.kobbler.app.ui.player.reportProgress
import dev.kobbler.app.ui.player.resumeRequestSeconds
import dev.kobbler.app.ui.player.resumeSeekSeconds
import dev.kobbler.app.ui.player.switchAudioAndResume
import dev.kobbler.kobplayer.Player
import kotlinx.coroutines.launch

class GpsReceiverPlayback(
    private val gps: GlobalPlayerState,
    private val player: Player,
    private val tvApi: () -> KobblerApi?,
) : ReceiverPlayback {

    override fun now(): ReceiverNow {
        val positionMs = gps.timeline.toAbsoluteMs(player.currentMs.value)
        val durationMs = gps.timeline.absoluteDurationMs(player.durationMs.value).takeIf { it > 0 }
            ?: ((gps.streamSession?.durationSeconds ?: 0.0) * 1000).toLong()
        val seconds = positionMs / 1000.0
        return ReceiverNow(
            mediaType = gps.mediaType,
            mediaId = gps.mediaId,
            title = gps.title,
            subtitle = gps.subtitle,
            posterUrl = gps.posterUrl,
            positionMs = positionMs,
            durationMs = durationMs,
            playing = player.isPlaying.value,
            skip = gps.skipSegments
                .firstOrNull { isVisibleSkipSegment(it) && seconds >= it.startSeconds && seconds < it.endSeconds }
                ?.let { CastSkip(it.segmentType, it.endSeconds) },
            tracks = castTracks(gps.renderableServerSubtitles, gps.selectedServerSubtitleTrackId, gps.serverAudioTracks, gps.selectedServerAudioIndex),
        )
    }

    override suspend fun open(api: KobblerApi, title: ReceiverTitle, positionSec: Int, onFinished: () -> Unit): Boolean {
        gps.beginPreparing(title.mediaId, title.title, title.subtitle, title.posterUrl, stream = false)
        val rebase = resumeRequestSeconds(positionSec)
        val session = try {
            when (title.mediaType) {
                "movie" -> api.getMovieStreamSession(title.mediaId, rebase)
                else -> api.getEpisodeStreamSession(title.mediaId, rebase)
            }
        } catch (e: Exception) {
            gps.cancelPreparing()
            throw e
        }
        if (session == null) {
            gps.cancelPreparing()
            return false
        }
        gps.load(
            player = player, api = api,
            type = title.mediaType, id = title.mediaId,
            streamUrl = session.url, newSessionId = session.sessionId,
            newTitle = title.title, newSubtitle = title.subtitle,
            newPosterUrl = title.posterUrl, newLogoUrl = null,
            destination = null,
            seekToSec = resumeSeekSeconds(positionSec, session.serverStartSeconds),
            session = session,
        )
        gps.onFinished = onFinished
        gps.isFullscreen = true
        gps.playUnmuted(player)
        return true
    }

    override fun releaseLocal() {
        val api = tvApi() ?: return
        saveAndStop(api)
    }

    override fun seekTo(absoluteMs: Long) = player.seekTo(gps.timeline.toPlayerMs(absoluteMs))

    override fun play() = player.play()

    override fun pause() = player.pause()

    override fun stop(api: KobblerApi) = saveAndStop(api)

    private fun saveAndStop(api: KobblerApi) {
        val type = gps.mediaType
        val id = gps.mediaId
        val positionMs = gps.timeline.toAbsoluteMs(player.currentMs.value)
        if (type != null && id != null && positionMs > 0) {
            gps.teardownScope.launch { gps.reportProgress(api, type, id, positionMs) }
        }
        gps.onFinished = null
        gps.stop(player, api, gps.teardownScope)
    }

    override fun selectSubtitle(trackId: Int?) {
        player.selectSubtitleTrack(null)
        gps.selectedServerSubtitleTrackId = subtitleTrackIdFor(trackId, gps.renderableServerSubtitles)
    }

    override suspend fun selectAudio(api: KobblerApi, streamIndex: Int) {
        if (streamIndex == gps.selectedServerAudioIndex) return
        val server = gps.serverAudioTracks
        val local = player.audioTracks.value
        // A direct-played file exposes all its tracks to the player, in the server's order.
        if (local.isNotEmpty() && local.size == server.size) {
            val track = local.getOrNull(server.indexOfFirst { it.streamIndex == streamIndex }) ?: return
            player.selectAudioTrack(track)
            gps.selectedServerAudioIndex = streamIndex
            return
        }
        val sessionId = gps.sessionId ?: return
        switchAudioAndResume(api, player, gps, sessionId, streamIndex)
    }
}
