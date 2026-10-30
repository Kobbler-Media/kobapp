package dev.kobbler.app.offline

interface RandomFile : AutoCloseable {
    fun write(offset: Long, bytes: ByteArray, length: Int)
    fun read(offset: Long, into: ByteArray, length: Int): Int
}

interface OfflineFs {
    fun read(path: String): ByteArray?
    /** Replaces [path] whole: a crash mid-write leaves the previous contents, never a torn file. */
    fun write(path: String, bytes: ByteArray)
    fun open(path: String): RandomFile
    fun delete(path: String)
    fun absolute(path: String): String
    fun usedBytes(): Long
    fun freeBytes(): Long
}

/** Null where the platform keeps nothing on disk (web, iOS): downloads are not offered there. */
expect fun platformOfflineFs(): OfflineFs?

/** Where downloads live: app storage the OS does not clear as cache. */
expect fun offlineRootDir(): String?
