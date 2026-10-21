package dev.kobbler.app.platform

import androidx.compose.runtime.Composable
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.request.allowPartialImage
import kotlin.concurrent.thread
import okio.Path
import okio.Path.Companion.toOkioPath
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual fun defaultFormFactor(): FormFactor {
    val ctx = try {
        // Read the application context cached from Settings.init()
        settingsContext
    } catch (_: Exception) { null }
    if (ctx != null) {
        // TV check must come first: leanback devices also report swDp >= 600,
        // and we want the 10-foot UI, not the tablet sidebar.
        if (ctx.packageManager.hasSystemFeature("android.software.leanback")) return FormFactor.TV
        val swDp = ctx.resources.configuration.smallestScreenWidthDp
        if (swDp >= 600) return FormFactor.Desktop // tablet/foldable
    }
    return FormFactor.Phone
}

// Stash app context for form factor detection
internal var settingsContext: android.content.Context? = null
actual val supportsVideoPlayback: Boolean = true

actual val deviceRegion: String? get() = java.util.Locale.getDefault().country.takeIf { it.isNotEmpty() }

actual val deviceLabel: String
    get() {
        val model = listOf(android.os.Build.MANUFACTURER, android.os.Build.MODEL)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "Android device" }
        // MODEL often already starts with the manufacturer ("Google Pixel 8" from
        // "Google" + "Pixel 8" reads fine, but "samsung SM-G991B" does not).
        return model.replaceFirstChar { it.uppercase() }
    }

actual val clientKind: String
    get() = if (defaultFormFactor() == FormFactor.TV) "androidtv" else "android"

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(enabled = enabled, onBack = onBack)
}

actual object Settings {
    // Android needs context — initialized from MainActivity
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: android.content.Context) {
        val loaded = context.getSharedPreferences("kobbler_app", android.content.Context.MODE_PRIVATE)
        prefs = loaded
        settingsContext = context.applicationContext
        // SharedPreferences parses its XML off-thread but the first accessor blocks until
        // that finishes. Do the blocking wait here rather than in first-frame composition.
        Thread { loaded.all }.start()
        PlaybackBridge.init(context)
    }

    actual fun getString(key: String): String? = prefs?.getString(key, null)
    actual fun putString(key: String, value: String) { prefs?.edit()?.putString(key, value)?.apply() }
    actual fun remove(key: String) { prefs?.edit()?.remove(key)?.apply() }
}

actual object AppVersion {
    actual val versionCode: Int get() {
        val ctx = settingsContext ?: return 0
        return try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).let {
                @Suppress("DEPRECATION") it.versionCode
            }
        } catch (_: Exception) { 0 }
    }
    actual val versionName: String get() {
        val ctx = settingsContext ?: return "unknown"
        return try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) { "unknown" }
    }
}

actual object FileCache {
    private var cacheDir: java.io.File? = null

    fun init(context: android.content.Context) {
        cacheDir = java.io.File(context.cacheDir, "books").also { it.mkdirs() }
    }

    actual fun get(key: String): ByteArray? {
        val file = cacheDir?.let { java.io.File(it, key) } ?: return null
        return if (file.exists()) file.readBytes() else null
    }
    actual fun put(key: String, data: ByteArray) {
        cacheDir?.let { java.io.File(it, key).writeBytes(data) }
    }

    actual fun pathFor(key: String): String? {
        val file = cacheDir?.let { java.io.File(it, key) } ?: return null
        return if (file.exists()) file.absolutePath else null
    }

    actual fun sink(key: String): CacheSink? {
        val dir = cacheDir ?: return null
        return CacheSink(java.io.File(dir, key), java.io.File(dir, "$key.part"))
    }
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
    retire(context.cacheDir.resolve("image_cache"))
    return context.cacheDir.resolve("artwork").toOkioPath()
}

// A truncated download decodes as the top of the picture over nothing, and is shown as if whole.
actual fun ImageLoader.Builder.applyPlatformImageOptions(): ImageLoader.Builder = allowPartialImage(false)

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
