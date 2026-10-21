package dev.kobbler.app.ui.player

import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.data.KobblerApi
import io.ktor.client.HttpClient
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A deleted session answers 410 for its thumbnails; asking every few seconds after that is dead polling. */
class ThumbnailsGoneTest {

    private val path = "/api/v1/admin/playback/sessions/s1/thumbnails.vtt"

    private fun serving(code: Int, block: suspend (KobblerApi, AtomicInteger) -> Unit) {
        val hits = AtomicInteger()
        StubServer(mapOf(path to { _: HttpExchange -> hits.incrementAndGet(); "" }), mapOf(path to code)).use { stub ->
            runTest { block(KobblerApi(HttpClient(), stub.url, "token"), hits) }
        }
    }

    @Test
    fun `waiting for thumbnails stops at the first 410`() = serving(410) { api, hits ->
        assertNull(awaitThumbnails(probe = { api.checkThumbnailsReady("s1") }, intervalMs = 10))
        assertEquals(1, hits.get())
    }

    @Test
    fun `thumbnails not generated yet are asked about again`() = serving(404) { api, hits ->
        assertNull(awaitThumbnails(probe = { api.checkThumbnailsReady("s1") }, intervalMs = 10, attempts = 3))
        assertEquals(3, hits.get())
    }

    @Test
    fun `the vtt of a gone session reads as gone`() = serving(410) { api, _ ->
        assertEquals(VttResponse.Gone, fetchThumbnailVtt("${api.apiUrl}$path", api.httpClient, "token"))
    }
}
