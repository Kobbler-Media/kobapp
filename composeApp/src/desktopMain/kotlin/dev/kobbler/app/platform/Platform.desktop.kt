package dev.kobbler.app.platform

import androidx.compose.runtime.Composable
import coil3.ImageLoader
import coil3.PlatformContext
import kotlin.concurrent.thread
import okio.Path
import okio.Path.Companion.toOkioPath
import java.util.prefs.Preferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual fun defaultFormFactor(): FormFactor = FormFactor.Desktop
actual val supportsVideoPlayback: Boolean = true

actual val deviceRegion: String? get() = java.util.Locale.getDefault().country.takeIf { it.isNotEmpty() }

private val resolvedDeviceLabel = lazy {
    val host = runCatching { java.net.InetAddress.getLocalHost().hostName }
        .getOrNull()
        ?.substringBefore('.')
        ?.takeIf { it.isNotBlank() }
    val os = System.getProperty("os.name")?.takeIf { it.isNotBlank() } ?: "Desktop"
    if (host != null) "$host ($os)" else os
}

// The hostname lookup can block for seconds on a host with broken name resolution, and
// the first reader is a request built on the UI thread; resolve it off-thread at startup.
@Suppress("unused")
private val deviceLabelWarmup = thread(isDaemon = true, name = "device-label") { resolvedDeviceLabel.value }

actual val deviceLabel: String get() = resolvedDeviceLabel.value

actual val clientKind: String = "desktop"

actual fun hostedServer(): dev.kobbler.app.data.HostedServer? = null

actual fun browserCapabilities(): dev.kobbler.app.data.ClientCapabilities? = null

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    // Desktop handles back via keyboard (Escape key) — no system back button
}

actual object Settings {
    private val prefs = Preferences.userRoot().node("dev/kobbler/app")
    actual fun getString(key: String): String? = prefs.get(key, null)
    actual fun putString(key: String, value: String) = prefs.put(key, value)
    actual fun remove(key: String) = prefs.remove(key)
}

actual object AppVersion {
    private val props = java.util.Properties().apply {
        AppVersion::class.java.getResourceAsStream("/kobapp-version.properties")?.use(::load)
    }
    actual val versionCode: Int = props.getProperty("versionCode")?.toIntOrNull() ?: 0
    actual val versionName: String = props.getProperty("versionName") ?: "dev"
}

actual object FileCache {
    private val cacheDir = java.io.File(System.getProperty("user.home"), ".cache/kobbler/books").also { it.mkdirs() }
    actual fun get(key: String): ByteArray? {
        val file = java.io.File(cacheDir, key)
        return if (file.exists()) file.readBytes() else null
    }
    actual fun put(key: String, data: ByteArray) {
        java.io.File(cacheDir, key).writeBytes(data)
    }

    actual fun pathFor(key: String): String? {
        val file = java.io.File(cacheDir, key)
        return if (file.exists()) file.absolutePath else null
    }

    actual fun sink(key: String): CacheSink? =
        CacheSink(java.io.File(cacheDir, key), java.io.File(cacheDir, "$key.part"))
}

actual class CacheSink internal constructor(
    private val target: java.io.File,
    private val partial: java.io.File,
) {
    private val out = java.io.BufferedOutputStream(java.io.FileOutputStream(partial))

    actual fun write(chunk: ByteArray, length: Int) { out.write(chunk, 0, length) }

    // Publish by rename so an interrupted download never leaves a truncated file under the key.
    actual fun commit() {
        out.close()
        target.delete()
        partial.renameTo(target)
    }

    actual fun abort() {
        runCatching { out.close() }
        partial.delete()
    }
}

actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

actual fun imageCacheDir(context: PlatformContext): Path? {
    val root = java.io.File(System.getProperty("user.home"), ".cache/kobbler")
    retire(root.resolve("images"))
    return root.resolve("artwork").toOkioPath()
}

actual fun ImageLoader.Builder.applyPlatformImageOptions(): ImageLoader.Builder = this

private fun retire(dir: java.io.File) {
    if (dir.exists()) thread(isDaemon = true, name = "retire-image-cache") { dir.deleteRecursively() }
}

actual suspend fun connectRoundTripMs(host: String, port: Int): Long? =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            // Resolved first: a DNS lookup is not the path the stream's bytes take.
            val address = java.net.InetSocketAddress(java.net.InetAddress.getByName(host), port)
            java.net.Socket().use { socket ->
                val start = System.nanoTime()
                socket.connect(address, 2_000)
                (System.nanoTime() - start) / 1_000_000
            }
        }.getOrNull()
    }
