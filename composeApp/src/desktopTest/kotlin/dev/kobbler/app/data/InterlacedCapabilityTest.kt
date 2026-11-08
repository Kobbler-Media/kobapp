package dev.kobbler.app.data

import io.ktor.client.HttpClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A decoder that cannot show interlaced video has to be kept from it whatever the source:
 * the server only sends the deinterlaced encode to a client that said so.
 */
class InterlacedCapabilityTest {

    @Test
    fun `every way of opening a stream says whether the decoder shows interlaced video`() = runTest {
        streamOpenStub().use { stub ->
            for (decodes in listOf(true, false)) {
                val api = KobblerApi(HttpClient(), stub.url, "token", decodesInterlaced = { decodes })
                stub.sentCapabilities(api).forEach { (path, capabilities) ->
                    assertEquals(JsonPrimitive(decodes), capabilities["supports_interlaced"], "$path decodes=$decodes")
                }
            }
        }
    }
}
