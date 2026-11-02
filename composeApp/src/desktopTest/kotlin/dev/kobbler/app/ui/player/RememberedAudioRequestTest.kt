package dev.kobbler.app.ui.player

import dev.kobbler.app.data.Episode
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.Season
import dev.kobbler.app.data.StreamOption
import dev.kobbler.app.data.StreamQuality
import dev.kobbler.app.data.TvShow
import dev.kobbler.app.data.UserSettings
import io.ktor.client.HttpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The track picked by hand on one episode is asked for by name when the next one starts. */
class RememberedAudioRequestTest {

    private val show = TvShow(
        id = "motogp", title = "MotoGP", year = 2026, posterUrl = "",
        seasons = listOf(Season(number = 1, episodes = listOf(Episode(id = "race", number = 1, title = "Race")))),
    )
    private val option = StreamOption(
        id = "o1", releaseTitle = "MotoGP.Race.mkv", size = 1, indexer = "idx", infoHash = "h1",
        quality = StreamQuality("1080p", null, null, null), qualityJson = null, totalScore = 1.0,
        debridProvider = "realdebrid", debridProviderId = "p1", cached = true,
        sourceType = "torrent", fileId = 1, publishDate = null,
    )
    private val session =
        """{"session_id":"s1","method":"direct_play","direct_url":"/api/v1/admin/stream/s1/play","duration_seconds":3600,""" +
            """"audio_tracks":[{"stream_index":1,"codec":"aac","language":"eng","title":"Commentary","is_default":true},""" +
            """{"stream_index":2,"codec":"aac","title":"Natural/Ambient","is_default":true}],"selected_audio_index":2}"""

    @BeforeTest
    fun pick() {
        UserSettings.bind { "remembered-audio-test" }
        rememberAudioChoice(playbackTitleKey("episode", "earlier", show.id), "Natural/Ambient")
    }

    @AfterTest
    fun forget() {
        rememberAudioChoice(playbackTitleKey("episode", "earlier", show.id), null)
        UserSettings.bind { null }
    }

    private fun sentTitle(body: String) =
        Json.parseToJsonElement(body).jsonObject["preferred_audio_title"]?.jsonPrimitive?.content

    @Test
    fun `a library start asks for it`() {
        val routes = mapOf(
            "/api/v1/admin/playback/sessions" to { _: com.sun.net.httpserver.HttpExchange -> session },
        )
        StubServer(routes).use { stub ->
            runBlocking {
                val api = KobblerApi(HttpClient(), stub.url, "token")
                startPlayback(api, "episode", "race", fromStart = true, audioTitle = rememberedAudioTitle("episode", "race", show.id))
            }
            assertEquals("Natural/Ambient", sentTitle(stub.requestBodies.getValue("/api/v1/admin/playback/sessions")))
        }
    }

    @Test
    fun `a stream start asks for it`() {
        val routes = mapOf(
            "/api/v1/admin/stream/resolve" to { _: com.sun.net.httpserver.HttpExchange -> session },
            "/api/v1/admin/stream/s1/outcome" to { _: com.sun.net.httpserver.HttpExchange -> "{}" },
        )
        StubServer(routes).use { stub ->
            runBlocking {
                val api = KobblerApi(HttpClient(), stub.url, "token")
                val player = FakePlayer().apply { durationMs.value = 3_600_000 }
                withTimeout(10_000) {
                    tryPlayTvWithFallback(
                        api = api, player = player, gps = GlobalPlayerState(), show = show,
                        episodeId = "race", seasonIdx = 0, episodeIdx = 0,
                        search = { found(listOf(option)) }, option = option,
                        onCaching = { _, _ -> error("cached") },
                    )
                    while (player.loadedSource == null) delay(10)
                }
            }
            assertEquals("Natural/Ambient", sentTitle(stub.requestBodies.getValue("/api/v1/admin/stream/resolve")))
        }
    }

    @Test
    fun `a prewarm asks for it`() {
        assertEquals("Natural/Ambient", nextEpisodeWarmTarget(show.copy(seasons = listOf(Season(1, listOf(
            Episode(id = "earlier", number = 1, title = "Q", filePath = "x"),
            Episode(id = "race", number = 2, title = "Race"),
        )))), "earlier")?.audioTitle)
    }
}
