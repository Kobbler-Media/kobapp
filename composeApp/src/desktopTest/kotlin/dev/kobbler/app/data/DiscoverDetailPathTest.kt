package dev.kobbler.app.data

import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class DiscoverDetailPathTest {

    @Test
    fun `a provider id holding slashes stays one path segment`() {
        var rawPath: String? = null
        val routes = mapOf<String, (HttpExchange) -> String>(
            "/api/v1/admin/discover/book/openlibrary//works/OL45883W" to { exchange ->
                rawPath = exchange.requestURI.rawPath
                """{"media_type":"book","provider":"openlibrary","details":{"title":"Dune"}}"""
            },
        )
        StubServer(routes).use { stub ->
            val detail = runBlocking { KobblerApi(HttpClient(), stub.url, "token").getDiscoverDetail("book", "openlibrary", "/works/OL45883W") }
            assertEquals("Dune", detail.title)
        }
        assertEquals("/api/v1/admin/discover/book/openlibrary/%2Fworks%2FOL45883W", rawPath)
    }
}
