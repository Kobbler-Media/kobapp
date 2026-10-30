package dev.kobbler.app.offline

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

data class Upstream(val url: String, val headers: Map<String, String> = emptyMap())

/** Hands out where a file's bytes come from; [refresh] asks for a new one after the last stopped answering. */
fun interface UpstreamSource {
    suspend fun get(refresh: Boolean): Upstream?
}

class UpstreamUnavailable(message: String) : Exception(message)

/** The server's file is no longer the one half of this download came from; it starts over. */
class SourceChanged(message: String) : Exception(message)

private class UpstreamGone(status: Int) : Exception("upstream answered $status")

/**
 * One downloaded file, read through: what is on disk is served from disk, the holes are fetched
 * and written down on the way past. The player's reads and the background filler both come
 * through here, so a stretch either of them has fetched is never fetched again.
 */
class OfflineMedia(
    private val store: OfflineStore,
    private val client: HttpClient,
    val key: String,
    val path: String,
    private val source: UpstreamSource,
) {
    private val sizeLock = Mutex()

    /** Where the player last read; the filler works forward from here so what plays next lands first. */
    var playhead: Long = 0
        private set

    var lastPlayerReadMs: Long = 0
        private set

    private fun file(): OfflineFile =
        store.item(key)?.file(path) ?: throw UpstreamUnavailable("$key/$path is not a download")

    suspend fun size(): Long = file().size ?: sizeLock.withLock {
        file().size ?: probe()
    }

    val contentType: String? get() = store.item(key)?.file(path)?.contentType

    private suspend fun probe(): Long {
        var size: Long? = null
        upstream { up ->
            request(up, 0, 1) { total, type, _ ->
                size = total
                store.updateFile(key, path) { it.copy(size = total, contentType = type ?: it.contentType) }
            }
        }
        return size ?: throw UpstreamUnavailable("$key/$path: no size from upstream")
    }

    suspend fun stream(start: Long, endExclusive: Long, fromPlayer: Boolean, sink: suspend (ByteArray, Int) -> Unit) {
        if (fromPlayer) {
            playhead = start
            lastPlayerReadMs = kotlin.time.Clock.System.now().toEpochMilliseconds()
        }
        val size = size()
        val end = endExclusive.coerceAtMost(size)
        var pos = start
        val buffer = ByteArray(ChunkBytes)
        val disk = store.randomFile(key, path)
        while (pos < end) {
            val ranges = file().ranges
            val filledEnd = ranges.filledEndFrom(pos)
            if (filledEnd != null) {
                val stop = minOf(filledEnd, end)
                while (pos < stop) {
                    val read = disk.read(pos, buffer, minOf(buffer.size.toLong(), stop - pos).toInt())
                    if (read <= 0) throw UpstreamUnavailable("$key/$path: short read at $pos")
                    sink(buffer, read)
                    pos += read
                    if (fromPlayer) playhead = pos
                }
            } else {
                val stop = minOf(ranges.gapEndFrom(pos, size), end)
                pos = fetch(pos, stop, disk) { bytes, length ->
                    sink(bytes, length)
                    if (fromPlayer) playhead += length
                }
            }
        }
    }

    private suspend fun fetch(from: Long, to: Long, disk: RandomFile, sink: suspend (ByteArray, Int) -> Unit): Long {
        var pos = from
        var committedFrom = from
        var committedAt = TimeSource.Monotonic.markNow()
        fun commit() {
            if (pos > committedFrom) {
                val span = committedFrom until pos
                store.updateFile(key, path, progressOnly = true) { it.copy(ranges = it.ranges + span) }
                committedFrom = pos
                committedAt = TimeSource.Monotonic.markNow()
            }
        }
        try {
            upstream { up ->
                request(up, pos, to) { _, _, body ->
                    body { bytes, length ->
                        val usable = minOf(length.toLong(), to - pos).toInt()
                        if (usable <= 0) return@body false
                        disk.write(pos, bytes, usable)
                        pos += usable
                        // Each commit copies the index and wakes everything watching it: often
                        // enough for a progress bar and for the player to read back, no more.
                        if (pos - committedFrom >= CommitBytes || committedAt.elapsedNow() >= CommitInterval) commit()
                        sink(bytes, usable)
                        pos < to
                    }
                }
                if (pos < to) throw UpstreamGone(0)
            }
        } finally {
            commit()
        }
        return pos
    }

    private suspend fun upstream(block: suspend (Upstream) -> Unit) {
        var refresh = false
        while (true) {
            val up = source.get(refresh) ?: throw UpstreamUnavailable("$key/$path: no upstream")
            try {
                return block(up)
            } catch (gone: UpstreamGone) {
                if (refresh) throw UpstreamUnavailable("$key/$path: ${gone.message}")
                refresh = true
            }
        }
    }

    private suspend fun request(
        up: Upstream,
        from: Long,
        to: Long,
        onResponse: suspend (
            total: Long?,
            contentType: String?,
            body: suspend (suspend (ByteArray, Int) -> Boolean) -> Unit,
        ) -> Unit,
    ) {
        client.prepareGet(up.url) {
            up.headers.forEach { (k, v) -> header(k, v) }
            header(HttpHeaders.Range, "bytes=$from-${to - 1}")
        }.execute { response ->
            val status = response.status.value
            if (status !in 200..299) throw UpstreamGone(status)
            val total = response.headers[HttpHeaders.ContentRange]?.substringAfter('/')?.toLongOrNull()
                ?: response.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.takeIf { status == 200 }
            val known = store.item(key)?.file(path)?.size
            if (known != null && total != null && total != known) {
                store.updateFile(key, path) { OfflineFile(it.path) }
                throw SourceChanged("$key/$path is $total bytes on the server, not the $known already kept")
            }
            // A server that ignores the Range header sends the file from its head.
            var skip = if (status == 200) from else 0L
            onResponse(total, response.headers[HttpHeaders.ContentType]) { consume ->
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(ChunkBytes)
                while (true) {
                    val read = channel.readAvailable(buffer, 0, buffer.size)
                    if (read < 0) break
                    if (read == 0) continue
                    if (skip >= read) { skip -= read; continue }
                    val offset = skip.toInt()
                    skip = 0
                    val chunk = if (offset == 0) buffer else buffer.copyOfRange(offset, read)
                    if (!consume(chunk, read - offset)) break
                }
            }
        }
    }

    private companion object {
        const val ChunkBytes = 64 * 1024
        const val CommitBytes = 8L * 1024 * 1024
        val CommitInterval = 1.seconds
    }
}
