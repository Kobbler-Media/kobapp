package dev.kobbler.app.ui.reader

import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.CacheSink
import dev.kobbler.app.platform.FileCache
import dev.kobbler.app.platform.ioDispatcher
import io.ktor.client.request.prepareGet
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

private const val DownloadChunkBytes = 64 * 1024

/**
 * Where a book archive ended up. [path] is set whenever the platform has a filesystem, so the
 * ZIP can be read entry by entry; [bytes] is the fallback for platforms that cannot (wasm).
 */
internal class BookPayload(val path: String?, val bytes: ByteArray?)

/**
 * Gets a book file onto the device, preferring disk. A large EPUB or CBZ is streamed straight
 * into the cache in [DownloadChunkBytes] chunks, so the archive never exists as one array in
 * the heap — buffering a 100MB book was an OutOfMemoryError against a 192-384MB growth limit.
 */
internal suspend fun fetchBookFile(
    api: KobblerApi,
    bookId: String,
    cacheKey: String,
    formats: List<String>,
): BookPayload = withContext(ioDispatcher) {
    FileCache.pathFor(cacheKey)?.let { return@withContext BookPayload(it, null) }
    FileCache.get(cacheKey)?.let { return@withContext BookPayload(null, it) }

    for (fmt in formats) {
        try {
            // prepareGet + execute, NOT get(): the plain form calls SavedCall.save(), which
            // reads the entire body into memory so the response outlives the call. That is a
            // 100MB array before any of this code runs. Only the prepared form streams.
            val stored = api.fileClient
                .prepareGet(api.bookFileUrl(bookId, format = fmt)) {
                    api.authHeaders.forEach { (k, v) -> header(k, v) }
                }
                .execute { response ->
                    if (response.status.value !in 200..299) return@execute false
                    val sink = FileCache.sink(cacheKey)
                    if (sink == null) {
                        // No filesystem (wasm): buffering is the only option.
                        FileCache.put(cacheKey, response.bodyAsBytes())
                        return@execute true
                    }
                    try {
                        val channel = response.bodyAsChannel()
                        val buffer = ByteArray(DownloadChunkBytes)
                        while (true) {
                            val read = channel.readAvailable(buffer, 0, buffer.size)
                            if (read < 0) break
                            if (read > 0) sink.write(buffer, read)
                        }
                        sink.commit()
                    } catch (e: Throwable) {
                        sink.abort()
                        throw e
                    }
                    true
                }
            if (stored) {
                val path = FileCache.pathFor(cacheKey)
                return@withContext BookPayload(path, if (path == null) FileCache.get(cacheKey) else null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
    throw Exception("No readable file available for this book.")
}
