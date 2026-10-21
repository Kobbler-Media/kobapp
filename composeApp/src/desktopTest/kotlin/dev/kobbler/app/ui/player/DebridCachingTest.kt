package dev.kobbler.app.ui.player

import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.data.KobblerApi
import io.ktor.client.HttpClient
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A release stuck at 0% read as a frozen progress bar; the viewer could not tell queued from broken. */
class DebridCachingTest {

    private val path = "/api/v1/admin/stream/resolve/status"

    @Test
    fun `a download that has not started says it is queued, then hands over when ready`() = runTest {
        val gps = GlobalPlayerState().apply { beginCachingPreparation("Show", onCancel = {}) }
        val notes = java.util.concurrent.CopyOnWriteArrayList<String?>()
        val route = { _: HttpExchange ->
            notes += gps.cachingNote
            val now = testScheduler.currentTime
            when {
                now < 30_000 -> """{"status":"downloading","progress":0}"""
                now < 40_000 -> """{"status":"downloading","progress":0.4}"""
                else -> """{"status":"ready"}"""
            }
        }
        StubServer(mapOf(path to route)).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")

            val failure = gps.awaitDebridCache(api, "p1", "t1", "Real-Debrid", timeSource = testTimeSource)

            assertNull(failure)
            assertEquals("Asking Real-Debrid to fetch it…", notes.first())
            assertTrue(notes.any { it?.startsWith("Queued on Real-Debrid") == true }, "never said it was queued: $notes")
            assertEquals("Downloading on Real-Debrid", gps.cachingNote)
            assertEquals(0.4f, gps.cachingProgress)
        }
    }

    @Test
    fun `an error from the debrid service ends the wait with its message`() = runTest {
        StubServer(mapOf(path to { _: HttpExchange -> """{"status":"error","message":"Torrent is dead"}""" })).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val gps = GlobalPlayerState().apply { beginCachingPreparation("Show", onCancel = {}) }

            val failure = gps.awaitDebridCache(api, "p1", "t1", "Real-Debrid", timeSource = testTimeSource)

            assertEquals("Torrent is dead", failure?.message)
        }
    }
}
