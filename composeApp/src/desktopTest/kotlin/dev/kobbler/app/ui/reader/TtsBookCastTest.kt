package dev.kobbler.app.ui.reader

import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.ui.player.FakeSender
import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue

/** The book goes with every read, so the server can read a cast book with its cast's voices. */
class TtsBookCastTest {

    private val routes = mapOf(
        "/api/v1/admin/tts/synthesize" to { _: HttpExchange -> """{"type":"done"}""" },
        "/api/v1/admin/tts/cast" to { _: HttpExchange -> """{"id":"r1"}""" },
        "/api/v1/admin/playback/tokens" to { _: HttpExchange -> """{"token":"cast-tok"}""" },
        "/api/v1/admin/tts/cast/r1/events" to { _: HttpExchange -> """{"type":"done"}""" + "\n" },
    )

    private fun bodyOf(path: String, receiver: Boolean): String = StubServer(routes).use { stub ->
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cast = FakeSender().apply { if (receiver) connect("Kitchen speaker") }
        val tts = TtsController(KobblerApi(HttpClient(), stub.url, "token"), scope, cast, bookId = "b1")
        try {
            tts.start("One two three four.")
            runBlocking {
                withTimeout(10_000) { while (stub.requestBodies[path] == null) delay(10) }
            }
            stub.requestBodies.getValue(path)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a read on the phone names its book`() {
        val body = bodyOf("/api/v1/admin/tts/synthesize", receiver = false)
        assertTrue(""""book_id":"b1"""" in body, body)
    }

    @Test
    fun `a read on a receiver names its book`() {
        val body = bodyOf("/api/v1/admin/tts/cast", receiver = true)
        assertTrue(""""book_id":"b1"""" in body, body)
    }
}
