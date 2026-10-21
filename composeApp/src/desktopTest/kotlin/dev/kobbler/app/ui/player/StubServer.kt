package dev.kobbler.app.ui.player

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** A JDK HTTP server that answers a fixed map of paths, so real API code can be driven. */
class StubServer(
    private val routes: Map<String, (HttpExchange) -> String>,
    private val codes: Map<String, Int> = emptyMap(),
    private val codeFor: (path: String, body: String) -> Int? = { _, _ -> null },
) : AutoCloseable {
    // Written from the server's pool, one thread per request in flight.
    val requestBodies: MutableMap<String, String> = ConcurrentHashMap()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val request = exchange.requestBody.readBytes().decodeToString()
            requestBodies[path] = request
            val handler = routes[path]
            val (code, body) = if (handler == null) 404 to "{}" else (codeFor(path, request) ?: codes[path] ?: 200) to handler(exchange)
            val bytes = body.encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        // A pool, not the dispatch thread: a test that asserts two calls overlap can only
        // observe that if the server is able to hold both at once.
        executor = Executors.newCachedThreadPool()
        start()
    }

    val url: String get() = "http://127.0.0.1:${server.address.port}"

    override fun close() = server.stop(0)
}
