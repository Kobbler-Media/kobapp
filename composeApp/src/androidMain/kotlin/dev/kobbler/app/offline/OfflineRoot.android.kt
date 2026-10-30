package dev.kobbler.app.offline

import dev.kobbler.app.platform.settingsContext

actual fun offlineRootDir(): String? {
    val context = settingsContext ?: return null
    val base = context.getExternalFilesDir(null) ?: context.filesDir
    return base.resolve("offline").absolutePath
}
