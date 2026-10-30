package dev.kobbler.app.offline

import dev.kobbler.app.data.KobblerApi

/** What [KobblerApi] asks of the offline layer; null on an api means nothing is ever kept. */
interface OfflineHooks {
    val isOffline: Boolean

    fun cachedResponse(path: String): String?

    suspend fun onResponse(path: String, body: String)

    fun onNetworkFailure()

    /** A progress write that could not reach the server; true when it was kept to send later. */
    fun queueWrite(owner: String, method: String, path: String, body: String): Boolean

    fun onWrite(path: String, body: String)

    /** Where the viewer got to in a title whose progress the server has not heard yet. */
    fun pendingResumeSeconds(owner: String, watchStatePath: String): Int?

    fun pendingWriteBody(owner: String, path: String): String?

    /** The session a downloaded title plays from, or null when [mediaType]/[mediaId] is not downloaded. */
    suspend fun playbackSession(
        mediaType: String,
        mediaId: String,
        mint: suspend () -> KobblerApi.StreamSession?,
    ): KobblerApi.StreamSession?

    fun audioUrl(bookId: String, fileId: String): String?

    fun bookFilePath(bookId: String, formats: List<String>): String?

    /** Served by the offline proxy on this device, so there is nothing to authenticate to. */
    fun servesLocally(url: String): Boolean

    /** A session the offline layer made up for a download: the server never heard of it. */
    fun isLocalSession(sessionId: String): Boolean
}

class OfflineException : Exception("Can't reach the server. Downloaded titles are still available in Offline.")
