package dev.kobbler.app.offline

import dev.kobbler.app.platform.ioDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

private const val ConnectTimeoutMs = 15_000
// A film is one response for as long as the player wants it, so only a socket that stops moving counts as hung.
private const val ReadTimeoutMs = 30_000

// HttpURLConnection, not ktor: ktor's channels allocate about a byte for every byte that passes
// through them, which on a TV-stick CPU is a collector running flat out for the whole download.
actual suspend fun <T> fetchMedia(url: String, headers: Map<String, String>, block: suspend (MediaResponse) -> T): T =
    withContext(ioDispatcher) {
        val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = ConnectTimeoutMs
            readTimeout = ReadTimeoutMs
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        // A blocking read ignores cancellation; dropping the socket is what ends it.
        val hangUp = currentCoroutineContext()[Job]?.invokeOnCompletion { connection.disconnect() }
        try {
            val status = connection.responseCode
            val body = if (status in 200..299) connection.inputStream else null
            block(object : MediaResponse {
                override val status = status
                override fun header(name: String): String? = connection.getHeaderField(name)
                override suspend fun read(into: ByteArray): Int {
                    currentCoroutineContext().ensureActive()
                    return body?.read(into) ?: -1
                }
            })
        } finally {
            hangUp?.dispose()
            runCatching { connection.inputStream.close() }
        }
    }
