package dev.kobbler.app.platform.dlna

import dev.kobbler.app.data.CreateSessionRequest
import dev.kobbler.app.data.SessionResponse
import dev.kobbler.app.data.withPlaybackToken
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class NextEpisode(
    @SerialName("episode_id") val episodeId: String,
    val title: String? = null,
    @SerialName("season_identifier") val seasonIdentifier: String = "",
    @SerialName("episode_identifier") val episodeIdentifier: String = "",
    @SerialName("show_title") val showTitle: String = "",
)

private val json = Json { ignoreUnknownKeys = true }

/**
 * The API as a cast receiver calls it: with the playback token it was handed, not a login.
 * A DLNA renderer cannot call it at all, so this device does it in its place.
 */
internal class ReceiverApi(private val http: HttpClient, val baseUrl: String, @Volatile var token: String) {
    fun resolve(url: String?): String? = url?.let { if ("://" in it) it else baseUrl.trimEnd('/') + it }

    private fun url(path: String) = withPlaybackToken(baseUrl.trimEnd('/') + path, token)

    suspend fun createSession(request: CreateSessionRequest): SessionResponse? = runCatching {
        val response = http.post(url("/api/v1/admin/playback/sessions")) {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(CreateSessionRequest.serializer(), request))
        }
        if (!response.status.isSuccess()) null
        else json.decodeFromString(SessionResponse.serializer(), response.bodyAsText())
    }.getOrNull()

    /**
     * A session on the track the server picked. Direct play serves the file whole and a
     * renderer plays its default audio, so any other track is asked for again as a stream
     * the server produces; a remux or transcode maps the picked track itself.
     */
    suspend fun openSession(request: CreateSessionRequest): SessionResponse? {
        val session = createSession(request) ?: return null
        val track = session.selectedAudioIndex ?: return session
        val default = session.audioTracks.firstOrNull { it.streamIndex == track }?.isDefault ?: true
        if (session.method != "direct_play" || default) return session
        closeSession(session.sessionId)
        return createSession(request.copy(audioTrackIndex = track, forceTranscode = true))
    }

    /** A session still preparing its source answers 503; a renderer handed it then gives up. */
    suspend fun awaitStream(url: String, attempts: Int = 60) {
        repeat(attempts) {
            val status = runCatching { http.prepareGet(url).execute { it.status } }.getOrNull()
            if (status != HttpStatusCode.ServiceUnavailable) return
            delay(500.milliseconds)
        }
    }

    suspend fun nextEpisode(episodeId: String): NextEpisode? = runCatching {
        val response = http.get(url("/api/v1/admin/tv/episodes/$episodeId/next"))
        if (!response.status.isSuccess()) null
        else json.decodeFromString(NextEpisode.serializer(), response.bodyAsText())
    }.getOrNull()

    suspend fun heartbeat(sessionId: String) {
        runCatching { http.get(url("/api/v1/admin/playback/sessions/$sessionId/heartbeat")) }
    }

    suspend fun closeSession(sessionId: String) {
        runCatching { http.delete(url("/api/v1/admin/playback/sessions/$sessionId")) }
    }
}
