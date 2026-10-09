package dev.kobbler.app.platform

import androidx.compose.runtime.Composable
import kotlinx.coroutines.CoroutineDispatcher

enum class FormFactor { Phone, TV, Desktop }

expect fun defaultFormFactor(): FormFactor
expect val supportsVideoPlayback: Boolean

/** Human-readable name for this install, shown in Kobbler's linked-devices list. */
expect val deviceLabel: String

/** Stable client family for this install: android, androidtv, desktop, ios, web. */
expect val clientKind: String

@Composable
expect fun BackHandler(enabled: Boolean = true, onBack: () -> Unit)

/** Simple key-value persistence for auth tokens etc. */
expect object Settings {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}

/**
 * Dispatcher for work that *blocks* its thread — [FileCache] and [Settings] reads, and
 * anything else that parks on disk. Dispatchers.Default is sized to core count for CPU work,
 * so a blocking read parked there costs a core; on a 4-core TV box that is a quarter of the
 * pool. wasmJs is single-threaded and has no Dispatchers.IO, so it falls back to Default.
 */
expect val ioDispatcher: CoroutineDispatcher

// Null keeps Coil's platform default. Only set where that default is wrong: on the JVM it
// is java.io.tmpdir, which is tmpfs on many Linux installs — RAM-backed and gone on reboot.
expect val imageCacheDir: String?

/**
 * Sequential writer into [FileCache]. Lets a multi-hundred-MB download reach disk without
 * ever existing as one array in the heap.
 */
expect class CacheSink {
    fun write(chunk: ByteArray, length: Int)
    /** Publishes the completed file under its key. */
    fun commit()
    /** Discards a partial write. */
    fun abort()
}

/** Simple binary file cache for downloaded books. */
expect object FileCache {
    fun get(key: String): ByteArray?
    fun put(key: String, data: ByteArray)

    /** Absolute path of the cached file, or null if absent or the platform has no filesystem. */
    fun pathFor(key: String): String?

    /** Streaming writer for [key], or null where the platform cannot stream to disk. */
    fun sink(key: String): CacheSink?
}

/** App version info for update checking. */
expect object AppVersion {
    val versionCode: Int
    val versionName: String
}
