package dev.kobbler.app.data

import io.ktor.client.HttpClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The server tone-maps HDR to SDR for a client that cannot show it, and a present list is
 * authoritative: an empty one means SDR-only, which is not the same as not saying.
 */
class HdrCapabilityTest {

    @Test
    fun `every way of opening a stream says which HDR formats the display shows`() = runTest {
        streamOpenStub().use { stub ->
            for (formats in listOf(listOf("hdr10", "hlg"), emptyList())) {
                val api = KobblerApi(HttpClient(), stub.url, "token", hdrFormats = { formats })
                stub.sentCapabilities(api).forEach { (path, capabilities) ->
                    assertEquals(JsonArray(formats.map(::JsonPrimitive)), capabilities["hdr_formats"], "$path formats=$formats")
                }
            }
        }
    }

    @Test
    fun `unknown HDR support is left out rather than sent as SDR-only`() = runTest {
        streamOpenStub().use { stub ->
            val unknown = ClientCapabilities(listOf("hevc"), listOf("aac"), listOf("mp4"))
            runCatching { KobblerApi(HttpClient(), stub.url, "token").createCastSession("movie", "m1", unknown, 0) }
            val sent = Json.parseToJsonElement(stub.requestBodies.getValue("/api/v1/admin/playback/sessions"))
                .jsonObject.getValue("capabilities").jsonObject
            assertFalse("hdr_formats" in sent, sent.toString())
        }
    }
}
