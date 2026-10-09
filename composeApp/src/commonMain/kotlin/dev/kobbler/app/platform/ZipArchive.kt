package dev.kobbler.app.platform

/** Simple multiplatform ZIP reader. Takes raw ZIP bytes, provides entry listing and reading. */
expect class ZipArchive(data: ByteArray) {
    /** List all entry paths in the archive. */
    fun entries(): List<String>

    /** Read an entry as UTF-8 text. Returns null if entry not found. */
    fun readText(path: String): String?

    /** Read an entry as raw bytes. Returns null if entry not found. */
    fun readBytes(path: String): ByteArray?

    /** Close/release resources. */
    fun close()
}

/**
 * Opens a ZIP already on disk, inflating entries on demand rather than holding the archive
 * in memory. Returns null where the platform has no filesystem (wasm) — callers fall back
 * to the [ZipArchive] byte constructor.
 */
expect fun openZipArchive(path: String): ZipArchive?
