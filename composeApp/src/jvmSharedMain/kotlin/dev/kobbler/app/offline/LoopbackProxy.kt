package dev.kobbler.app.offline

import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * A plain HTTP/1.1 range server on loopback. libVLC asks for byte ranges and nothing else, so
 * a socket and a thread per connection are all it takes — idle, it costs one blocked thread.
 */
actual fun startOfflineProxy(handler: ProxyHandler): Int? = runCatching {
    val server = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
    thread(name = "offline-proxy", isDaemon = true) {
        while (!server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: continue
            thread(name = "offline-proxy-conn", isDaemon = true) { serve(socket, handler) }
        }
    }
    server.localPort
}.getOrNull()

private fun serve(socket: Socket, handler: ProxyHandler): Unit = socket.use {
    socket.tcpNoDelay = true
    val input = BufferedInputStream(socket.getInputStream())
    val output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
    try {
        while (true) {
            val requestLine = input.readLine() ?: return
            if (requestLine.isBlank()) continue
            val (method, target) = requestLine.split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "/") }
            val headers = generateSequence { input.readLine()?.takeIf { it.isNotEmpty() } }
                .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
            val keepAlive = !headers["connection"].equals("close", ignoreCase = true)
            val (rangeStart, rangeEnd) = parseRange(headers["range"])
            val response = runBlocking { handler.handle(target.substringBefore('?'), rangeStart, rangeEnd) }
            when (response) {
                ProxyResponse.NotFound -> {
                    output.writeHead("404 Not Found", listOf("Content-Length" to "0"), keepAlive)
                    output.flush()
                }
                is ProxyResponse.Content -> {
                    val length = response.endExclusive - response.start
                    val head = buildList {
                        add("Content-Type" to (response.contentType ?: "application/octet-stream"))
                        add("Content-Length" to length.toString())
                        add("Accept-Ranges" to "bytes")
                        if (response.partial) {
                            add("Content-Range" to "bytes ${response.start}-${response.endExclusive - 1}/${response.totalSize}")
                        }
                    }
                    output.writeHead(if (response.partial) "206 Partial Content" else "200 OK", head, keepAlive)
                    if (method != "HEAD" && length > 0) {
                        runBlocking { response.body { bytes, count -> output.write(bytes, 0, count) } }
                    }
                    output.flush()
                }
            }
            if (!keepAlive) return
        }
    } catch (_: Exception) {
        // A player that seeks closes the socket under us; a hole that cannot be fetched ends the
        // response short, and libVLC reconnects for what it still wants.
    }
}

private fun parseRange(header: String?): Pair<Long?, Long?> {
    val spec = header?.removePrefix("bytes=")?.substringBefore(',') ?: return null to null
    val start = spec.substringBefore('-').toLongOrNull() ?: return null to null
    return start to spec.substringAfter('-').toLongOrNull()
}

private fun java.io.OutputStream.writeHead(status: String, headers: List<Pair<String, String>>, keepAlive: Boolean) {
    val text = buildString {
        append("HTTP/1.1 ").append(status).append("\r\n")
        headers.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
        append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n\r\n")
    }
    write(text.encodeToByteArray())
}

private fun InputStream.readLine(): String? {
    val line = StringBuilder()
    while (true) {
        val c = read()
        if (c < 0) return if (line.isEmpty()) null else line.toString()
        if (c == '\n'.code) return line.toString().trimEnd('\r')
        line.append(c.toChar())
    }
}
