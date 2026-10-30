package dev.kobbler.app.offline

sealed interface ProxyResponse {
    data object NotFound : ProxyResponse

    class Unsatisfiable(val totalSize: Long) : ProxyResponse

    class Content(
        val contentType: String?,
        val totalSize: Long,
        val start: Long,
        val endExclusive: Long,
        val partial: Boolean,
        val body: suspend (sink: suspend (ByteArray, Int) -> Unit) -> Unit,
    ) : ProxyResponse
}

fun interface ProxyHandler {
    /** [rangeEnd] is inclusive, as HTTP writes it; null on both is the whole file. */
    suspend fun handle(path: String, rangeStart: Long?, rangeEnd: Long?): ProxyResponse
}

/** Binds loopback on a free port and serves [handler] until the process ends; null where unsupported. */
expect fun startOfflineProxy(handler: ProxyHandler): Int?

/** `/o/<item key>/<file path>`: a download's media through [OfflineMedia], its small files straight from disk. */
class OfflineRoutes(
    private val store: OfflineStore,
    private val media: suspend (key: String, path: String) -> OfflineMedia?,
) : ProxyHandler {
    override suspend fun handle(path: String, rangeStart: Long?, rangeEnd: Long?): ProxyResponse {
        val parts = path.removePrefix("/").split('/', limit = 4)
        // Loopback is reachable by every app on the device: only a URL this process handed
        // out names a download, and only a file the download lists is ever read.
        if (parts.size < 4 || parts[0] != RoutePrefix || parts[1] != ProxySecret) return ProxyResponse.NotFound
        val key = parts[2]
        val file = parts[3]
        val item = store.item(key) ?: return ProxyResponse.NotFound
        if (item.file(file) == null && file !in item.extras && !SpritePath.matches(file)) return ProxyResponse.NotFound
        if (item.file(file) != null) {
            val source = media(key, file) ?: return ProxyResponse.NotFound
            val size = runCatching { source.size() }.getOrElse { return ProxyResponse.NotFound }
            if (rangeStart != null && rangeStart >= size) return ProxyResponse.Unsatisfiable(size)
            val start = (rangeStart ?: 0).coerceIn(0, size)
            val end = ((rangeEnd ?: (size - 1)) + 1).coerceIn(start, size)
            return ProxyResponse.Content(source.contentType, size, start, end, partial = rangeStart != null) { sink ->
                source.stream(start, end, fromPlayer = true, sink)
            }
        }
        val bytes = store.readWhole(key, file) ?: return ProxyResponse.NotFound
        val size = bytes.size.toLong()
        if (rangeStart != null && rangeStart >= size) return ProxyResponse.Unsatisfiable(size)
        val start = (rangeStart ?: 0).coerceIn(0, size)
        val end = ((rangeEnd ?: (size - 1)) + 1).coerceIn(start, size)
        return ProxyResponse.Content(contentTypeFor(file), size, start, end, partial = rangeStart != null) { sink ->
            val slice = bytes.copyOfRange(start.toInt(), end.toInt())
            sink(slice, slice.size)
        }
    }

    companion object {
        const val RoutePrefix = "o"
    }
}

private val SpritePath = Regex("^$ThumbnailsDir/[A-Za-z0-9_-]+\\.(jpg|jpeg|png|webp)$")

// Uuid.random() draws from a cryptographically secure source; another app must not guess it.
@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
private val ProxySecret: String = kotlin.uuid.Uuid.random().toHexString()

fun offlineBase(port: Int) = "http://127.0.0.1:$port/${OfflineRoutes.RoutePrefix}/$ProxySecret/"

fun offlineUrl(port: Int, key: String, path: String) = "${offlineBase(port)}$key/$path"

private fun contentTypeFor(path: String) = when (path.substringAfterLast('.').lowercase()) {
    "vtt" -> "text/vtt"
    "srt" -> "application/x-subrip"
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    else -> "application/octet-stream"
}
