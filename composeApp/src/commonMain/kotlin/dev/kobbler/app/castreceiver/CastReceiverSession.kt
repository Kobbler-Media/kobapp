package dev.kobbler.app.castreceiver

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.kobbler.app.data.ApiException
import dev.kobbler.app.data.CastSkip
import dev.kobbler.app.data.CastTracks
import dev.kobbler.app.data.ClientCapabilities
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.CastFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

private const val PositionEveryMs = 5_000L
private val processStart = TimeSource.Monotonic.markNow()

/** What kobapp does as a receiver: video only, saved as the sender. Books and read-alouds stay with the web receiver. */
val CastConnectFeatures = setOf(CastFeature.ReceiverReportsProgress, CastFeature.Hls)

class CastReceiverSession(
    private val playback: ReceiverPlayback,
    private val send: (String) -> Unit,
    private val capabilities: () -> ClientCapabilities,
    private val scope: CoroutineScope,
    private val apiFor: (baseUrl: String, credential: SenderCredential) -> KobblerApi = { url, c -> senderApi(url, c) },
    private val clockMs: () -> Long = { processStart.elapsedNow().inWholeMilliseconds },
) {
    private var api by mutableStateOf<KobblerApi?>(null)
    private var owned by mutableStateOf(emptySet<String>())
    private var credential: SenderCredential? = null
    private var title: ReceiverTitle? = null
    private var expiredLoad: ReceiverLoad? = null
    private var opening = false

    private var positionSentAt: Long? = null
    private var announcedPlaying: Boolean? = null
    private var announcedSkip: CastSkip? = null
    private var announcedTracks: CastTracks? = null

    val isActive: Boolean get() = api != null

    fun apiFor(mediaId: String?): KobblerApi? = api?.takeIf { mediaId == null || mediaId in owned }

    fun playbackNow(): ReceiverNow = playback.now()

    suspend fun load(load: ReceiverLoad): Boolean {
        val local = playback.now().mediaId
        if (local != null && local !in owned) playback.releaseLocal()
        val credential = SenderCredential(load.session.token)
        val api = apiFor(load.session.baseUrl, credential)
        this.credential = credential
        expiredLoad = null
        return open(api, load.title, load.positionSec) { expiredLoad = load }
    }

    private suspend fun open(api: KobblerApi, title: ReceiverTitle, positionSec: Int, onExpired: () -> Unit = {}): Boolean {
        owned = owned + title.mediaId
        this.api = api
        this.title = title
        opening = true
        val opened = try {
            playback.open(api, title, positionSec, ::onTitleFinished)
        } catch (e: Exception) {
            if ((e as? ApiException)?.statusCode != 401) {
                end()
                throw e
            }
            onExpired()
            send(ReceiverReplies.expired)
            false
        } finally {
            opening = false
        }
        resetAnnouncements()
        if (!opened) end()
        return opened
    }

    suspend fun message(json: String) {
        when (val command = parseReceiverCommand(json)) {
            is ReceiverCommand.SubtitleChange -> if (isActive) {
                playback.selectSubtitle(command.trackId)
                announceTracks()
            }
            is ReceiverCommand.AudioSwitch -> api?.let {
                playback.selectAudio(it, command.streamIndex)
                announceTracks()
            }
            is ReceiverCommand.TokenRefresh -> {
                credential?.token = command.token
                expiredLoad?.let { load(it.copy(session = it.session.copy(token = command.token))) }
            }
            ReceiverCommand.StatusRequest -> send(ReceiverReplies.status(playback.now()))
            ReceiverCommand.CapabilitiesRequest -> send(ReceiverReplies.capabilities(capabilities(), CastConnectFeatures))
            ReceiverCommand.TracksRequest -> if (isActive) announceTracks()
            is ReceiverCommand.Unplayable -> send(ReceiverReplies.refused(command.type))
            is ReceiverCommand.Unsupported, null -> Unit
        }
    }

    fun seek(absoluteMs: Long) { if (isActive) playback.seekTo(absoluteMs) }
    fun play() { if (isActive) playback.play() }
    fun pause() { if (isActive) playback.pause() }

    /** The sender ended the cast. It takes the watch back itself, so nothing is said. */
    fun stop() {
        val api = api ?: return
        playback.stop(api)
        end()
    }

    fun senderConnected() {
        send(ReceiverReplies.capabilities(capabilities(), CastConnectFeatures))
        if (isActive) announceTracks()
    }

    fun observe(now: ReceiverNow) {
        if (!isActive || opening) return
        if (now.mediaId == null || now.mediaId !in owned) {
            end()
            send(ReceiverReplies.finished)
            return
        }
        if (now.skip != announcedSkip) {
            announcedSkip = now.skip
            send(ReceiverReplies.skip(now.skip))
        }
        if (now.tracks != announcedTracks) {
            announcedTracks = now.tracks
            send(ReceiverReplies.tracks(now.tracks))
        }
        val at = clockMs()
        val due = positionSentAt?.let { at - it >= PositionEveryMs && now.playing } ?: true
        if (due || announcedPlaying != now.playing) {
            send(ReceiverReplies.position(now))
            positionSentAt = at
            announcedPlaying = now.playing
        }
    }

    private fun onTitleFinished() {
        scope.launch { titleFinished() }
    }

    private suspend fun titleFinished() {
        val api = api ?: return
        val finished = title ?: return
        val next = if (finished.mediaType == "episode") {
            runCatching { api.nextEpisode(finished.mediaId) }.getOrNull()
        } else null
        if (next != null) {
            val opened = runCatching {
                open(api, ReceiverTitle("episode", next.episodeId, next.showTitle, next.label, finished.posterUrl), 0)
            }.getOrDefault(false)
            if (opened) return send(ReceiverReplies.episodeChanged(next))
        }
        playback.stop(api)
        end()
        send(ReceiverReplies.finished)
    }

    private fun announceTracks() {
        val tracks = playback.now().tracks
        announcedTracks = tracks
        send(ReceiverReplies.tracks(tracks))
    }

    private fun resetAnnouncements() {
        positionSentAt = null
        announcedPlaying = null
        announcedSkip = null
        announcedTracks = null
    }

    private fun end() {
        api = null
        owned = emptySet()
        title = null
        credential = null
    }
}
