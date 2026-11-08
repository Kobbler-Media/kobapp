package dev.kobbler.app.data

import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.ui.player.StubServer
import dev.kobbler.app.ui.player.debridOption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

internal val streamOpens: List<Pair<String, suspend KobblerApi.() -> Unit>> = listOf(
    "/api/v1/admin/playback/sessions" to { getMovieStreamSession("m1") },
    "/api/v1/admin/stream/resolve" to { streamResolve(debridOption("h1"), "movie", "m1") },
    "/api/v1/admin/stream/prewarm" to { prewarmStream(StreamTarget.movie("m1")) },
    "/api/v1/admin/iptv/sessions" to { playIptvChannel("c1") },
    "/api/v1/admin/livetv/sessions" to { openLiveSession("c1") },
    "/api/v1/admin/livetv/sessions/s1/channel" to { switchLiveSessionChannel("s1", "c1") },
)

internal fun streamOpenStub() = StubServer(streamOpens.associate { (path, _) -> path to { _: HttpExchange -> "{}" } })

internal suspend fun StubServer.sentCapabilities(api: KobblerApi): Map<String, JsonObject> =
    streamOpens.associate { (path, open) ->
        runCatching { api.open() }
        path to Json.parseToJsonElement(requestBodies.getValue(path)).jsonObject["capabilities"] as JsonObject
    }
