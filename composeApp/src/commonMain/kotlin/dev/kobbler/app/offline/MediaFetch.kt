package dev.kobbler.app.offline

interface MediaResponse {
    val status: Int
    fun header(name: String): String?
    /** Bytes read into [into], or -1 at the end of the body. */
    suspend fun read(into: ByteArray): Int
}

/** A plain GET read straight into the caller's buffer: a film goes through here a chunk at a time, so nothing may be allocated per chunk. */
expect suspend fun <T> fetchMedia(url: String, headers: Map<String, String>, block: suspend (MediaResponse) -> T): T
