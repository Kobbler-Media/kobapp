package dev.kobbler.app.platform.dlna

import dev.kobbler.app.data.AudioTrackDto
import dev.kobbler.app.data.CastAudioTrack
import dev.kobbler.app.data.CastInitSession
import dev.kobbler.app.data.CastSkip
import dev.kobbler.app.data.CastTextTrack
import dev.kobbler.app.data.CastTracks
import dev.kobbler.app.data.ClientCapabilities
import dev.kobbler.app.data.CreateSessionRequest
import dev.kobbler.app.data.SkipSegment
import dev.kobbler.app.data.TrackPrefs
import dev.kobbler.app.platform.CastEvent
import dev.kobbler.app.platform.CastLoad
import io.ktor.client.HttpClient
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val payloadJson = Json { ignoreUnknownKeys = true }

/** Nobody is at the TV to press Skip: these go by on their own. */
private val AutoSkipped = setOf("intro", "recap")

/** A stop this close to the end is the title running out, not the viewer stopping it. */
private const val EndSlackSec = 30.0

private const val Progressive = "progressive"

private enum class Ending { Finished, StoppedThere }

/**
 * What the Kobbler cast receiver does for itself, done from this device for a renderer
 * that can only be told to play a URL: following the position, keeping the session alive,
 * skipping, going on to the next episode, and reopening the stream to seek or switch tracks.
 */
internal class DlnaPlayback(
    private val transport: AvTransport,
    private val capabilities: ClientCapabilities,
    private val http: HttpClient,
    private val scope: CoroutineScope,
    private val emit: suspend (CastEvent) -> Unit,
    private val onStoppedThere: suspend () -> Unit,
    private val pollEvery: Duration,
    private val heartbeatEvery: Duration = 30.seconds,
) {
    private val lock = Mutex()
    private var api: ReceiverApi? = null
    private var stream: DlnaStream? = null
    private var title = ""
    private var subtitle: String? = null
    private var posterUrl: String? = null
    private var subtitleTrack: Int? = null
    private var audioIndex: Int? = null
    private var playing = false
    private var sawPlaying = false
    private var positionSec = 0.0
    private var announcedSkip: CastSkip? = null
    private var skipAnnounced = false
    private val skipped = HashSet<SkipSegment>()
    private var loops: Job? = null

    suspend fun load(load: CastLoad) {
        val init = payloadJson.decodeFromString(CastInitSession.serializer(), load.payloadJson)
        lock.withLock {
            val api = ReceiverApi(http, init.baseUrl, init.token).also { this.api = it }
            val stream = init.toStream(api).also { this.stream = it }
            title = load.title
            subtitle = load.subtitle
            posterUrl = load.imageUrl
            subtitleTrack = null
            audioIndex = null
            start(stream.offsetSec + load.startPositionSec)
        }
        if (loops == null) loops = scope.launch {
            launch {
                while (true) {
                    delay(pollEvery)
                    poll()
                }
            }
            launch {
                while (true) {
                    delay(heartbeatEvery)
                    lock.withLock { stream?.sessionId }?.let { api?.heartbeat(it) }
                }
            }
        }
    }

    suspend fun handle(type: String, payload: String) {
        when (type) {
            "CAPABILITIES_REQUEST" -> emit(CastEvent.Capabilities(capabilities))
            "TRACKS_REQUEST" -> emit(CastEvent.TracksChanged(lock.withLock { tracks() }))
            "STATUS_REQUEST" -> emit(lock.withLock { position() })
            "SUBTITLE_CHANGE" -> {
                val id = field(payload, "trackId")?.intOrNull
                emit(CastEvent.TracksChanged(lock.withLock { subtitleTrack = id; reload(positionSec); tracks() }))
            }
            "AUDIO_SWITCH" -> {
                val index = field(payload, "streamIndex")?.intOrNull ?: return
                emit(CastEvent.TracksChanged(lock.withLock { audioIndex = index; reopen(positionSec); tracks() }))
            }
            "TOKEN_REFRESH" -> field(payload, "token")?.contentOrNull?.let { api?.token = it }
        }
    }

    suspend fun seekTo(positionSec: Int) = lock.withLock { seek(positionSec.toDouble()) }

    suspend fun togglePlayPause() = lock.withLock {
        runCatching { if (playing) transport.pause() else transport.play() }
            .onSuccess { playing = !playing }
    }

    suspend fun stop() {
        loops?.cancel()
        loops = null
        lock.withLock {
            runCatching { transport.stop() }
            stream?.let { api?.closeSession(it.sessionId) }
            stream = null
        }
    }

    private suspend fun poll() {
        val ending = lock.withLock { observe() } ?: return
        when (ending) {
            Ending.Finished -> advance()
            Ending.StoppedThere -> onStoppedThere()
        }
    }

    private suspend fun observe(): Ending? {
        val s = stream ?: return null
        val state = runCatching { transport.state() }.getOrNull() ?: return null
        if (state == TransportState.Playing) sawPlaying = true
        if (sawPlaying && (state == TransportState.Stopped || state == TransportState.NoMedia)) {
            sawPlaying = false
            playing = false
            return if (s.durationSec > 0 && positionSec >= s.durationSec - EndSlackSec) Ending.Finished else Ending.StoppedThere
        }
        val reported = runCatching { transport.position() }.getOrNull() ?: return null
        if (state == TransportState.Transitioning) return null
        playing = state == TransportState.Playing
        positionSec = s.offsetSec + reported.positionSec
        emit(position())
        followSkips(s)
        return null
    }

    private suspend fun followSkips(s: DlnaStream) {
        val segment = s.skipSegments.firstOrNull { positionSec >= it.startSeconds && positionSec < it.endSeconds - 1 }
        val skip = segment?.let { CastSkip(it.segmentType, it.endSeconds) }
        if (!skipAnnounced || skip != announcedSkip) {
            skipAnnounced = true
            announcedSkip = skip
            emit(CastEvent.SkipChanged(skip))
        }
        if (segment != null && segment.segmentType in AutoSkipped && skipped.add(segment)) seek(segment.endSeconds)
    }

    private suspend fun advance() = lock.withLock {
        val s = stream ?: return@withLock
        val api = api ?: return@withLock
        emit(CastEvent.Position(s.durationSec, s.durationSec, isPlaying = false))
        val episode = s.mediaId?.takeIf { s.mediaType == "episode" }?.let { api.nextEpisode(it) }
        val session = episode?.let { api.createSession(request(s.copy(mediaId = it.episodeId), atSec = null)) }
        if (episode == null || session == null) {
            emit(CastEvent.Finished)
            return@withLock
        }
        api.closeSession(s.sessionId)
        val label = listOfNotNull(
            "S${episode.seasonIdentifier} E${episode.episodeIdentifier}",
            episode.title?.takeIf { it.isNotBlank() },
        ).joinToString(" • ")
        stream = session.toStream(api, episode.episodeId, "episode")
        title = episode.showTitle.ifBlank { title }
        subtitle = label
        subtitleTrack = null
        audioIndex = null
        start(0.0)
        emit(CastEvent.EpisodeChanged(episode.episodeId, episode.showTitle, label))
    }

    private suspend fun start(atSec: Double) {
        val s = stream ?: return
        val url = s.url ?: return
        sawPlaying = false
        skipAnnounced = false
        skipped.clear()
        positionSec = atSec
        if (s.progressiveUrl != null) api?.awaitStream(url)
        runCatching {
            transport.setUri(url, didlLite(listOfNotNull(title, subtitle).joinToString(" · "), url, s.protocolInfo, posterUrl, subtitleUrl(s)))
            transport.play()
        }
        if (s.seekable && atSec >= 1) {
            repeat(20) {
                if (runCatching { transport.state() }.getOrNull() == TransportState.Playing) {
                    runCatching { transport.seek(atSec.toInt()) }
                    return
                }
                delay(250.milliseconds)
            }
        }
    }

    private suspend fun seek(atSec: Double) {
        val s = stream ?: return
        if (!s.seekable) return reopen(atSec)
        runCatching { transport.seek(atSec.toInt()) }
        positionSec = atSec
    }

    /** Same stream, new metadata: what a renderer needs to pick up another sidecar. */
    private suspend fun reload(atSec: Double) {
        val s = stream ?: return
        if (s.seekable) start(atSec) else reopen(atSec)
    }

    private suspend fun reopen(atSec: Double) {
        val s = stream ?: return
        val api = api ?: return
        val mediaId = s.mediaId ?: return
        val session = api.createSession(request(s, atSec.toInt())) ?: return
        api.closeSession(s.sessionId)
        stream = session.toStream(api, mediaId, s.mediaType)
        start(atSec)
    }

    private fun request(s: DlnaStream, atSec: Int?): CreateSessionRequest {
        val prefs = TrackPrefs.current()
        val audio: AudioTrackDto? = s.audioTracks.firstOrNull { it.streamIndex == audioIndex }
        return CreateSessionRequest(
            mediaType = s.mediaType,
            mediaId = s.mediaId.orEmpty(),
            capabilities = capabilities,
            // A renderer plays a file's default audio and has no way to be told otherwise.
            forceTranscode = audio != null && !audio.isDefault,
            preferredAudioLanguage = audio?.language ?: prefs.audioLanguage,
            preferredAudioTitle = audio?.title,
            preferredSubtitleLanguage = prefs.subtitleLanguage,
            // Text subtitles ride as a sidecar; only a burn-in would need the server.
            subtitlesEnabled = false,
            startTime = atSec,
            delivery = Progressive,
        )
    }

    private fun subtitleUrl(s: DlnaStream): String? {
        val track = subtitleTrack?.let { s.textTracks.getOrNull(it) } ?: return null
        val url = api?.resolve(track.srtUrl) ?: return null
        return if (s.offsetSec > 0) "$url${if ('?' in url) '&' else '?'}offset=${s.offsetSec}" else url
    }

    private fun position(): CastEvent.Position =
        CastEvent.Position(positionSec, stream?.durationSec ?: 0.0, playing)

    private fun tracks(): CastTracks {
        val s = stream ?: return CastTracks()
        return CastTracks(
            subtitles = s.textTracks.mapIndexed { i, t ->
                CastTextTrack(i, t.language, t.languageName, t.title, t.isForced, t.isHearingImpaired)
            },
            activeSubtitleId = subtitleTrack,
            audioTracks = s.audioTracks.map { CastAudioTrack(it.streamIndex, it.language, it.languageName, it.title, it.codec, it.channels) },
            activeAudioIndex = audioIndex ?: s.audioTracks.firstOrNull { it.isDefault }?.streamIndex,
        )
    }

    private fun field(payload: String, key: String) =
        runCatching { Json.parseToJsonElement(payload).jsonObject[key]?.jsonPrimitive }.getOrNull()
}
