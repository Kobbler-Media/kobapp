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

actual object FileCache {
    // WASM: no filesystem. Use in-memory cache only (cleared on page refresh).
    // Could use IndexedDB for persistence but that requires async APIs.
    private val cache = mutableMapOf<String, ByteArray>()

    actual fun get(key: String): ByteArray? = cache[key]
    actual fun put(key: String, data: ByteArray) { cache[key] = data }

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
