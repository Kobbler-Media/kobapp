package dev.kobbler.app.data

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibraryEventStreamTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = scope.cancel()

    /** Answers [status]; a 200 sends one frame and then holds the connection open. */
    private class SseStub(private val status: Int = 200) : AutoCloseable {
        val connections = AtomicInteger()
        private val release = CountDownLatch(1)
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api/v1/admin/events") { exchange ->
                connections.incrementAndGet()
                if (status != 200) {
                    exchange.sendResponseHeaders(status, -1)
                    exchange.close()
                    return@createContext
                }
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, 0)
                val out = exchange.responseBody
                out.write("event: movie_updated\ndata: {\"kind\":\"movie_updated\"}\n\n".encodeToByteArray())
                out.flush()
                release.await(10, TimeUnit.SECONDS)
                runCatching { out.close() }
            }
            executor = Executors.newCachedThreadPool()
            start()
        }
        val url get() = "http://127.0.0.1:${server.address.port}"
        override fun close() {
            release.countDown()
            server.stop(0)
        }
    }

    private fun hub(stub: SseStub, foreground: MutableStateFlow<Boolean> = MutableStateFlow(true)) =
        SharedEventStream(
            upstream = libraryEventStream(HttpClient(), stub.url, "t", json),
            foreground = foreground,
            scope = scope,
        )

    private fun eventually(what: String, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!check()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError(what)
            Thread.sleep(20)
        }
    }

    @Test
    fun `every listener shares one connection`() {
        SseStub().use { stub ->
            val events = hub(stub)
            val seen = CopyOnWriteArrayList<String>()
            repeat(3) { scope.launch { events.events.collect { seen += it.kind } } }
            eventually("no listener got the frame") { seen.count { it == "movie_updated" } == 3 }
            Thread.sleep(200)
            assertEquals(1, stub.connections.get(), "each listener opened its own stream")
        }
    }

    @Test
    fun `a listener that joins an open stream is told it is connected`() {
        SseStub().use { stub ->
            val events = hub(stub)
            val first = CopyOnWriteArrayList<String>()
            scope.launch { events.events.collect { first += it.kind } }
            eventually("first listener never connected") { "movie_updated" in first }
            val late = CopyOnWriteArrayList<String>()
            scope.launch { events.events.collect { late += it.kind } }
            eventually("a late listener never heard the stream was up") { STREAM_CONNECTED in late }
        }
    }

    @Test
    fun `nothing connects while the app is in the background`() {
        SseStub().use { stub ->
            val foreground = MutableStateFlow(false)
            val events = hub(stub, foreground)
            val seen = CopyOnWriteArrayList<String>()
            scope.launch { events.events.collect { seen += it.kind } }
            Thread.sleep(300)
            assertEquals(0, stub.connections.get(), "connected while backgrounded")
            foreground.value = true
            eventually("never connected on return to the foreground") { STREAM_CONNECTED in seen }
            foreground.value = false
            Thread.sleep(200)
            seen.clear()
            foreground.value = true
            eventually("did not reconnect after a second return") { STREAM_CONNECTED in seen }
            assertEquals(2, stub.connections.get())
        }
    }

    @Test
    fun `a rejected stream is not retried at the network-error pace`() = runBlocking {
        SseStub(status = 401).use { stub ->
            val policy = ReconnectPolicy(baseMs = 10, maxMs = 40, rejectedMs = 60_000)
            val job = scope.launch {
                sseFramesForTest(HttpClient(), "${stub.url}/api/v1/admin/events", "t", json, policy).collect {}
            }
            Thread.sleep(600)
            job.cancel()
            assertEquals(1, stub.connections.get(), "a 401 was retried as if the network had dropped")
        }
    }

    @Test
    fun `a dropped stream backs off but keeps trying`() = runBlocking {
        SseStub(status = 503).use { stub ->
            val policy = ReconnectPolicy(baseMs = 10, maxMs = 40, rejectedMs = 60_000)
            withTimeout(5_000) {
                val job = scope.launch {
                    sseFramesForTest(HttpClient(), "${stub.url}/api/v1/admin/events", "t", json, policy).collect {}
                }
                eventually("a server error stopped the retries") { stub.connections.get() >= 4 }
                job.cancel()
            }
            assertTrue(stub.connections.get() >= 4)
        }
    }

    @Test
    fun `backoff doubles to its cap and resets after a connection`() {
        val p = ReconnectPolicy(baseMs = 5_000, maxMs = 60_000, rejectedMs = 300_000)
        assertEquals(5_000, p.next(previousMs = 0, outcome = StreamOutcome.Dropped))
        assertEquals(10_000, p.next(previousMs = 5_000, outcome = StreamOutcome.Dropped))
        assertEquals(60_000, p.next(previousMs = 40_000, outcome = StreamOutcome.Dropped))
        assertEquals(5_000, p.next(previousMs = 60_000, outcome = StreamOutcome.Connected))
        assertEquals(300_000, p.next(previousMs = 5_000, outcome = StreamOutcome.Rejected))
    }

    @Test
    fun `a debrid session stream ends when the server says it is done`() = runBlocking {
        val connections = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api/v1/admin/stream/s1/status") { exchange ->
                connections.incrementAndGet()
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use {
                    it.write("data: {\"type\":\"skip_segments_ready\"}\n\ndata: {\"type\":\"done\"}\n\n".encodeToByteArray())
                }
            }
            executor = Executors.newCachedThreadPool()
            start()
        }
        try {
            val kinds = withTimeout(10_000) {
                debridSessionEventStream(HttpClient(), "http://127.0.0.1:${server.address.port}", "s1", "t", json)
                    .toList()
                    .map { it.kind }
            }
            assertEquals(listOf("skip_segments_ready"), kinds.filter { !it.startsWith("_") })
            assertEquals(1, connections.get(), "reconnected to a finished session")
        } finally {
            server.stop(0)
        }
    }
}
