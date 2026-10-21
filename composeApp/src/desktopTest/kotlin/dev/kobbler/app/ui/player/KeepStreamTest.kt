package dev.kobbler.app.ui.player

import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.data.KeepState
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.ui.components.DataCache
import io.ktor.client.HttpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Keep turns the stream being watched into a library file, saying so quietly as it goes. */
class KeepStreamTest {

    @Test
    fun `keep posts once, reports progress over the player, and refreshes the title when saved`() {
        val posts = AtomicInteger()
        val polls = AtomicInteger()
        val route = { ex: HttpExchange ->
            if (ex.requestMethod == "POST") {
                posts.incrementAndGet()
                """{"job_id":"j1","state":"queued"}"""
            } else when (polls.incrementAndGet()) {
                1 -> """{"job_id":"j1","state":"downloading","progress":40}"""
                2 -> """{"job_id":"j1","state":"importing"}"""
                else -> """{"job_id":"j1","state":"done"}"""
            }
        }
        StubServer(mapOf("/api/v1/admin/stream/s1/keep" to route)).use { stub ->
            runBlocking {
                val api = KobblerApi(HttpClient(), stub.url, "token")
                val gps = GlobalPlayerState().apply { sessionId = "s1" }
                val notes = CopyOnWriteArrayList<String?>()
                val before = DataCache.versionOf("movie-m1")
                withTimeout(10_000) {
                    gps.keep(api, "s1", pollMs = 20)
                    gps.keep(api, "s1", pollMs = 20)
                    while (gps.keeps["s1"]?.state != KeepState.Done) {
                        if (notes.lastOrNull() != gps.playerNote) notes += gps.playerNote
                        delay(2)
                    }
                }
                assertEquals(1, posts.get(), "a second press started a second import")
                assertTrue("Saving to library… 40%" in notes, "no progress was shown: $notes")
                assertEquals("Saved", gps.playerNote)
                assertTrue(DataCache.versionOf("movie-m1") > before, "the title was not refreshed to pick up its file")
            }
        }
    }
}
