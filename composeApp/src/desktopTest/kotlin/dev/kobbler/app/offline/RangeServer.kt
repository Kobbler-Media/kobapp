package dev.kobbler.app.offline

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Serves byte ranges of fixed files, counting what it sent, and can be told to refuse or vanish. */
class RangeServer(initial: Map<String, ByteArray>) : AutoCloseable {
    val files: MutableMap<String, ByteArray> = java.util.concurrent.ConcurrentHashMap(initial)
    val bytesServed = AtomicLong()
    val requests = AtomicInteger()
    @Volatile var down = false
    @Volatile var goneOnce: Set<String> = emptySet()

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            requests.incrementAndGet()
            val path = exchange.requestURI.path
            val data = files[path]
            when {
                down -> { exchange.close(); return@createContext }
                path in goneOnce -> {
                    goneOnce = goneOnce - path
                    exchange.sendResponseHeaders(404, -1)
                    exchange.close()
                    return@createContext
                }
                data == null -> {
                    exchange.sendResponseHeaders(404, -1)
                    exchange.close()
                    return@createContext
                }
            }
            val range = exchange.requestHeaders.getFirst("Range")?.removePrefix("bytes=")
            val start = range?.substringBefore('-')?.toLongOrNull() ?: 0L
            val end = range?.substringAfter('-')?.toLongOrNull()?.plus(1)?.coerceAtMost(data!!.size.toLong()) ?: data!!.size.toLong()
            exchange.responseHeaders.add("Content-Type", "video/x-matroska")
            if (range != null) exchange.responseHeaders.add("Content-Range", "bytes $start-${end - 1}/${data.size}")
            exchange.sendResponseHeaders(if (range != null) 206 else 200, end - start)
            exchange.responseBody.use { out ->
                out.write(data, start.toInt(), (end - start).toInt())
                bytesServed.addAndGet(end - start)
            }
        }
        executor = Executors.newCachedThreadPool()
        start()
    }

    fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    override fun close() = server.stop(0)
}
