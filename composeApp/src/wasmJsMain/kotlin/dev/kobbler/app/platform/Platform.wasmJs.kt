package dev.kobbler.app.platform

import androidx.compose.runtime.Composable
import coil3.ImageLoader
import coil3.PlatformContext
import okio.Path
import kotlinx.browser.localStorage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual fun defaultFormFactor(): FormFactor = FormFactor.Desktop
actual val supportsVideoPlayback: Boolean = false

actual val deviceRegion: String?
    get() = kotlinx.browser.window.navigator.language.substringAfter('-', "").uppercase().takeIf { it.length == 2 }

actual val deviceLabel: String = "Web app"

actual val clientKind: String = "web"

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    // Web handles back via browser navigation — no system back button
}

actual object Settings {
    actual fun getString(key: String): String? = localStorage.getItem(key)
    actual fun putString(key: String, value: String) { localStorage.setItem(key, value) }
    actual fun remove(key: String) { localStorage.removeItem(key) }
}

actual object AppVersion {
    actual val versionCode: Int = 0
    actual val versionName: String = "web"
}

// No filesystem here, so the cache is the tab's heap: bounded, least recently used out.
private const val FileCacheMaxBytes = 200L * 1024 * 1024

actual object FileCache {
    private val cache = LinkedHashMap<String, ByteArray>()
    private var bytes = 0L

    actual fun get(key: String): ByteArray? {
        val data = cache.remove(key) ?: return null
        cache[key] = data
        return data
    }

    actual fun put(key: String, data: ByteArray) {
        cache.remove(key)?.let { bytes -= it.size }
        cache[key] = data
        bytes += data.size
        val keys = cache.keys.iterator()
        while (bytes > FileCacheMaxBytes && cache.size > 1) {
            val oldest = keys.next()
            bytes -= cache.getValue(oldest).size
            keys.remove()
        }
    }

    actual fun pathFor(key: String): String? = null
    actual fun sink(key: String): CacheSink? = null
}

/**
 * No streaming writer on this platform: [FileCache.sink] always returns null and callers
 * fall back to buffering the payload.
 */
actual class CacheSink private constructor() {
    actual fun write(chunk: ByteArray, length: Int) = Unit
    actual fun commit() = Unit
    actual fun abort() = Unit
}



// Single-threaded runtime: there is no separate I/O pool to move to.
actual val ioDispatcher: CoroutineDispatcher = Dispatchers.Default

actual fun imageCacheDir(context: PlatformContext): Path? = null

actual fun ImageLoader.Builder.applyPlatformImageOptions(): ImageLoader.Builder = this

actual suspend fun connectRoundTripMs(host: String, port: Int): Long? = null
