package dev.kobbler.app.ui.player

import dev.kobbler.app.data.Episode
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.Season
import dev.kobbler.app.data.StreamOption
import dev.kobbler.app.data.StreamQuality
import dev.kobbler.app.data.TvShow
import dev.kobbler.app.data.UserSettings
import dev.kobbler.app.ui.dashboard.KEY_DEFAULT_AUDIO_LANG
import io.ktor.client.HttpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A debrid stream is the whole release file, so whatever track the release marked default
 * plays unless the start says otherwise — an Italian dub over an English preference.
 */
class DebridAudioLanguageTest {

    private val show = TvShow(
        id = "show-1", title = "Show", year = 2020, posterUrl = "",
        seasons = listOf(Season(number = 1, episodes = listOf(Episode(id = "ep-1", number = 1, title = "One")))),
    )

    private val option = StreamOption(
        id = "o1", releaseTitle = "Show.S01E01.ITA.ENG.mkv", size = 1, indexer = "idx", infoHash = "h1",
        quality = StreamQuality("1080p", null, null, null), qualityJson = null, totalScore = 1.0,
        debridProvider = "realdebrid", debridProviderId = "p1", cached = true,
        sourceType = "torrent", fileId = 1, publishDate = null,
    )

    private fun session(extra: String = "") =
        """{"session_id":"s1","method":"direct_play","direct_url":"/api/v1/admin/stream/s1/play","duration_seconds":3600,""" +
            """"audio_tracks":[{"stream_index":1,"codec":"ac3","language":"ita","is_default":true},""" +
            """{"stream_index":2,"codec":"ac3","language":"eng"}]$extra}"""

    private fun startsWith(preferred: String?, resolveBody: String): Pair<String?, String> {
        UserSettings.bind { "debrid-audio-test" }
        if (preferred != null) UserSettings.putString(KEY_DEFAULT_AUDIO_LANG, preferred)
        try {
            val routes = mapOf(
                "/api/v1/admin/stream/resolve" to { _: com.sun.net.httpserver.HttpExchange -> resolveBody },
                "/api/v1/admin/stream/s1/outcome" to { _: com.sun.net.httpserver.HttpExchange -> "{}" },
            )
            return StubServer(routes).use { stub ->
                runBlocking {
                    val api = KobblerApi(HttpClient(), stub.url, "token")
                    val player = FakePlayer().apply { durationMs.value = 3_600_000 }
                    withTimeout(10_000) {
                        tryPlayTvWithFallback(
                            api = api, player = player, gps = GlobalPlayerState(), show = show,
                            episodeId = "ep-1", seasonIdx = 0, episodeIdx = 0,
                            search = { found(listOf(option)) }, option = option,
                            onCaching = { _, _ -> error("cached") },
                        )
                        while (player.loadedSource == null) delay(10)
                    }
                    player.loadedSource?.audioLanguage to stub.requestBodies.getValue("/api/v1/admin/stream/resolve")
                }
            }
        } finally {
            UserSettings.remove(KEY_DEFAULT_AUDIO_LANG)
            UserSettings.bind { null }
        }
    }

    @Test
    fun `an Italian-default release starts in English when English is preferred`() {
        val (language, request) = startsWith("eng", session())
        assertEquals("eng", language)
        val sent = Json.parseToJsonElement(request).jsonObject["preferred_audio_language"]?.jsonPrimitive?.content
        assertEquals("eng", sent, "the server was not told the preference")
    }

    @Test
    fun `the server's preferred audio stream wins when it names one`() {
        val (language, _) = startsWith(null, session(""","preferred_audio_stream_index":2"""))
        assertEquals("eng", language)
    }
}
