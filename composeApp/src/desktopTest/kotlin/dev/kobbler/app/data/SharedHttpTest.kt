package dev.kobbler.app.data

import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class SharedHttpTest {

    @Test
    fun `every client the app makes rides one engine`() {
        val api = KobblerApi(SharedHttp.apiClient(), "http://127.0.0.1:9", "token")
        val engines = listOf(
            SharedHttp.apiClient(),
            SharedHttp.imageClient { null },
            api.httpClient,
            api.fileClient,
            api.eventClient,
        ).map { it.engine }
        assertEquals(1, engines.distinct().size, "each client opened its own connection pool")
    }

    @Test
    fun `closing a client leaves the shared engine serving the others`() = runBlocking<Unit> {
        val auth = mutableListOf<String?>()
        StubServer(mapOf<String, (HttpExchange) -> String>("/ping" to { ex -> auth += ex.requestHeaders.getFirst("Authorization"); "pong" })).use { stub ->
            SharedHttp.apiClient().close()
            val images = SharedHttp.imageClient { url -> if (url.endsWith("/ping")) "img" else null }
            assertEquals("pong", images.get("${stub.url}/ping").bodyAsText())
            assertEquals("pong", SharedHttp.apiClient().get("${stub.url}/ping") { header("Authorization", "Bearer api") }.bodyAsText())
            assertEquals(listOf<String?>("Bearer img", "Bearer api"), auth)
        }
    }
}
