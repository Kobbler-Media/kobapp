package dev.kobbler.app.platform

import androidx.compose.runtime.Composable
import coil3.ImageLoader
import coil3.PlatformContext
import okio.Path
import kotlinx.browser.localStorage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// The screen, not the window: a narrowed desktop browser stays Desktop, and the same 600dp
// line Android draws between phone and tablet holds in CSS pixels.
actual fun defaultFormFactor(): FormFactor {
    val screen = kotlinx.browser.window.screen
    return if (minOf(screen.width, screen.height) < 600) FormFactor.Phone else FormFactor.Desktop
}
actual val supportsVideoPlayback: Boolean = true

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

    actual fun remove(key: String) {
        cache.remove(key)?.let { bytes -= it.size }
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

private const val HOSTED_PATH = "/player/"

actual fun hostedServer(): dev.kobbler.app.data.HostedServer? {
    val location = kotlinx.browser.window.location
    val at = location.pathname.indexOf(HOSTED_PATH)
    if (at < 0) return null
    return dev.kobbler.app.data.HostedServer(location.origin + location.pathname.substring(0, at)) { apiUrl ->
        val back = location.pathname + location.search + location.hash
        location.assign("$apiUrl/?next=${encodeURIComponent(back)}")
    }
}

private fun encodeURIComponent(value: String): String = js("encodeURIComponent(value)")

private val VideoProbes = listOf(
    "h264" to "video/mp4; codecs=\"avc1.42E01E\"",
    "hevc" to "video/mp4; codecs=\"hvc1.1.6.L93.B0\"",
    "vp9" to "video/mp4; codecs=\"vp09.00.10.08\"",
    "av1" to "video/mp4; codecs=\"av01.0.05M.08\"",
)

private val AudioProbes = listOf(
    "aac" to "audio/mp4; codecs=\"mp4a.40.2\"",
    "ac3" to "audio/mp4; codecs=\"ac-3\"",
    "eac3" to "audio/mp4; codecs=\"ec-3\"",
    "opus" to "audio/mp4; codecs=\"opus\"",
    "flac" to "audio/mp4; codecs=\"flac\"",
    "mp3" to "audio/mpeg",
)

// Everything not direct-playable arrives as fMP4 over HLS, so mp4 is the only container.
actual fun browserCapabilities(): dev.kobbler.app.data.ClientCapabilities? =
    dev.kobbler.app.data.ClientCapabilities(
        videoCodecs = (listOf("h264") + VideoProbes.filter { mseSupports(it.second) }.map { it.first }).distinct(),
        audioCodecs = (listOf("aac") + AudioProbes.filter { mseSupports(it.second) }.map { it.first }).distinct(),
        containers = listOf("mp4"),
    )

private fun mseSupports(mime: String): Boolean =
    js("typeof MediaSource !== 'undefined' && MediaSource.isTypeSupported(mime)")
