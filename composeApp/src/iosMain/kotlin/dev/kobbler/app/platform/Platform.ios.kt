package dev.kobbler.app.platform

import androidx.compose.runtime.Composable
import coil3.ImageLoader
import coil3.PlatformContext
import okio.Path
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSUserDefaults
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSLocale
import platform.Foundation.countryCode
import platform.Foundation.create
import platform.Foundation.currentLocale
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile

actual fun defaultFormFactor(): FormFactor {
    // Use Desktop layout (sidebar) on iPad
    return if (platform.UIKit.UIDevice.currentDevice.userInterfaceIdiom ==
        platform.UIKit.UIUserInterfaceIdiomPad
    ) FormFactor.Desktop else FormFactor.Phone
}
actual val supportsVideoPlayback: Boolean = true

actual val deviceRegion: String? get() = NSLocale.currentLocale.countryCode

actual val deviceLabel: String
    get() = platform.UIKit.UIDevice.currentDevice.name

actual val clientKind: String = "ios"

actual fun hostedServer(): dev.kobbler.app.data.HostedServer? = null

actual fun browserCapabilities(): dev.kobbler.app.data.ClientCapabilities? = null

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    // iOS handles back via swipe gesture natively
}

actual object Settings {
    private val defaults = NSUserDefaults.standardUserDefaults
    actual fun getString(key: String): String? = defaults.stringForKey(key)
    actual fun putString(key: String, value: String) { defaults.setObject(value, key) }
    actual fun remove(key: String) { defaults.removeObjectForKey(key) }
}

actual object AppVersion {
    private val info = NSBundle.mainBundle.infoDictionary
    actual val versionCode: Int = (info?.get("CFBundleVersion") as? String)?.toIntOrNull() ?: 0
    actual val versionName: String = info?.get("CFBundleShortVersionString") as? String ?: "dev"
}

@OptIn(ExperimentalForeignApi::class)
actual object FileCache {
    private val cacheDir: String by lazy {
        val paths = platform.Foundation.NSSearchPathForDirectoriesInDomains(
            platform.Foundation.NSCachesDirectory, platform.Foundation.NSUserDomainMask, true
        )
        val base = (paths.firstOrNull() as? String) ?: "/tmp"
        val dir = "$base/kobbler/books"
        platform.Foundation.NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, null)
        dir
    }

    actual fun get(key: String): ByteArray? {
        val data = NSData.dataWithContentsOfFile("$cacheDir/$key") ?: return null
        return data.toByteArray()
    }

    actual fun put(key: String, data: ByteArray) {
        val nsData = data.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = data.size.toULong())
        }
        nsData.writeToFile("$cacheDir/$key", true)
    }

    actual fun remove(key: String) {
        platform.Foundation.NSFileManager.defaultManager.removeItemAtPath("$cacheDir/$key", null)
    }

    @Suppress("UNCHECKED_CAST")
    private fun NSData.toByteArray(): ByteArray {
        val size = length.toInt()
        val result = ByteArray(size)
        if (size > 0) {
            result.usePinned { pinned ->
                platform.posix.memcpy(pinned.addressOf(0), bytes, length)
            }
        }
        return result
    }

    actual fun pathFor(key: String): String? {
        val path = "$cacheDir/$key"
        return if (platform.Foundation.NSFileManager.defaultManager.fileExistsAtPath(path)) path else null
    }

    // Streaming to disk needs NSFileHandle interop that has not been exercised on a Mac yet.
    actual fun sink(key: String): CacheSink? = null
}

actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

/**
 * No streaming writer on this platform: [FileCache.sink] always returns null and callers
 * fall back to buffering the payload.
 */
actual class CacheSink private constructor() {
    actual fun write(chunk: ByteArray, length: Int) = Unit
    actual fun commit() = Unit
    actual fun abort() = Unit
}

actual fun imageCacheDir(context: PlatformContext): Path? = null

actual fun ImageLoader.Builder.applyPlatformImageOptions(): ImageLoader.Builder = this

actual suspend fun connectRoundTripMs(host: String, port: Int): Long? = null
