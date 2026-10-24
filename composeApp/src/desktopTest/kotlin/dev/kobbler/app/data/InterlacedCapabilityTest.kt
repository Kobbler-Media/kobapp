package dev.kobbler.app.data

import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.ui.player.StubServer
import dev.kobbler.app.ui.player.debridOption
import io.ktor.client.HttpClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A decoder that cannot show interlaced video has to be kept from it whatever the source:
 * the server only sends the deinterlaced encode to a client that said so.
 */
class InterlacedCapabilityTest {

    private val opens: List<Pair<String, suspend KobblerApi.() -> Unit>> = listOf(
        "/api/v1/admin/playback/sessions" to { getMovieStreamSession("m1") },
        "/api/v1/admin/stream/resolve" to { streamResolve(debridOption("h1"), "movie", "m1") },
        "/api/v1/admin/stream/prewarm" to { prewarmStream(StreamTarget.movie("m1")) },
        "/api/v1/admin/iptv/sessions" to { playIptvChannel("c1") },
        "/api/v1/admin/livetv/sessions" to { openLiveSession("c1") },
        "/api/v1/admin/livetv/sessions/s1/channel" to { switchLiveSessionChannel("s1", "c1") },
    )

    @Test
    fun `every way of opening a stream says whether the decoder shows interlaced video`() = runTest {
        StubServer(opens.associate { (path, _) -> path to { _: HttpExchange -> "{}" } }).use { stub ->
            for (decodes in listOf(true, false)) {
                val api = KobblerApi(HttpClient(), stub.url, "token", decodesInterlaced = { decodes })
                for ((path, open) in opens) {
                    runCatching { api.open() }
                    val capabilities = Json.parseToJsonElement(stub.requestBodies.getValue(path))
                        .jsonObject["capabilities"] as JsonObject
                    assertEquals(JsonPrimitive(decodes), capabilities["supports_interlaced"], "$path decodes=$decodes")
                }
            }
        }
    }
}
