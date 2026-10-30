package dev.kobbler.app.offline

actual fun offlineRootDir(): String? = null

actual fun platformOfflineFs(): OfflineFs? = null

actual fun startOfflineProxy(handler: ProxyHandler): Int? = null

actual suspend fun <T> fetchMedia(url: String, headers: Map<String, String>, block: suspend (MediaResponse) -> T): T =
    throw UpstreamUnavailable("Downloads are not supported on this platform")
