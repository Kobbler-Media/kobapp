package dev.kobbler.app.data

import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class StreamAvailabilityTest {

    @Test
    fun `availability is read from the search and absent stays null`() {
        val body = """{"options":[
            {"id":"a","release_title":"A","debrid_provider":"Real-Debrid","debrid_provider_id":"p1","availability":"likely"},
            {"id":"b","release_title":"B","debrid_provider":"Real-Debrid","debrid_provider_id":"p1"}]}"""
        StubServer(mapOf("/api/v1/admin/stream/search" to { _: HttpExchange -> body })).use { stub ->
            val result = runBlocking { KobblerApi(HttpClient(), stub.url, "token").streamSearch(episodeId = "ep-1") }
            assertEquals(listOf("likely", null), result.options.map { it.availability })
        }
    }
}
