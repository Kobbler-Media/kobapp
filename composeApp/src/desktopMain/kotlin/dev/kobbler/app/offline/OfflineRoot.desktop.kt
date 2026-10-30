package dev.kobbler.app.offline

import java.io.File

actual fun offlineRootDir(): String? {
    val dataHome = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let(::File)
        ?: File(System.getProperty("user.home"), ".local/share")
    return dataHome.resolve("kobbler/offline").absolutePath
}
