package dev.kobbler.app.castreceiver

import dev.kobbler.app.data.ApiException
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.SharedHttp
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestPipeline
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile

class SenderCredential(token: String) {
    @Volatile
    var token: String = token
}

/**
 * An API client that is the sender, not this TV: a playback token is only accepted as
 * `?token=` (the server reads a header login as a session token and refuses it), so the
 * header [KobblerApi] adds is swapped for the query on every request.
 */
fun senderApi(baseUrl: String, credential: SenderCredential, client: HttpClient = SharedHttp.apiClient()): KobblerApi {
    client.requestPipeline.intercept(HttpRequestPipeline.State) {
        context.headers.remove(HttpHeaders.Authorization)
        context.url.parameters["token"] = credential.token
    }
    return KobblerApi(client, baseUrl.trimEnd('/'), token = "")
}

@Serializable
data class NextEpisode(
    @SerialName("episode_id") val episodeId: String,
    val title: String? = null,
    @SerialName("episode_identifier") val episodeIdentifier: String,
    @SerialName("season_identifier") val seasonIdentifier: String,
    @SerialName("show_title") val showTitle: String,
) {
    val label: String get() = listOfNotNull("S$seasonIdentifier E$episodeIdentifier", title?.takeIf { it.isNotBlank() }).joinToString(" • ")
}

private val nextJson = Json { ignoreUnknownKeys = true }

suspend fun KobblerApi.nextEpisode(episodeId: String): NextEpisode? {
    val response = httpClient.get("$apiUrl/api/v1/admin/tv/episodes/$episodeId/next")
    if (response.status == HttpStatusCode.Unauthorized) throw ApiException(401, "Unauthorized")
    if (!response.status.isSuccess()) return null
    return nextJson.decodeFromString(NextEpisode.serializer(), response.bodyAsText())
}
