package dev.kobbler.app.offline

import dev.kobbler.app.data.ApiException
import dev.kobbler.app.data.KobblerApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock

/** Every response fetched inside it is kept for [owner], so its pages open with no network. */
class OfflineCapture(val owner: String) : AbstractCoroutineContextElement(OfflineCapture) {
    companion object Key : CoroutineContext.Key<OfflineCapture>
}

class OfflineEngine(
    val store: OfflineStore,
    val reachability: Reachability,
    private val scope: CoroutineScope,
    startProxy: (ProxyHandler) -> Int? = ::startOfflineProxy,
    internal val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : OfflineHooks {
    private val _api = MutableStateFlow<KobblerApi?>(null)
    val api: StateFlow<KobblerApi?> = _api.asStateFlow()

    private val mediaLock = Mutex()
    private val media = mutableMapOf<String, OfflineMedia>()
    private val directUrls = mutableMapOf<String, String>()
    private val downloadSessions = mutableMapOf<String, String>()

    val port: Int? = startProxy(OfflineRoutes(store, ::media))

    val downloader = OfflineDownloader(this, scope)

    val maintenance = OfflineMaintenance(this, scope)

    init {
        scope.launch {
            reachability.online.drop(1).filter { it }.collect { replayWrites() }
        }
    }

    /**
     * What is finished, as its own flow: a library grid marks cards from this, and it only
     * changes when a download completes or goes, not on every progress tick.
     */
    val completeKeys: StateFlow<Set<String>> = store.index
        .map { it.completeKeys() }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, store.index.value.completeKeys())

    val completeShows: StateFlow<Set<String>> = store.index
        .map { it.completeShows() }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, store.index.value.completeShows())

    /** One download's state, changing only when that download does. */
    fun item(key: String): kotlinx.coroutines.flow.Flow<OfflineItem?> = store.index.map { it.items[key] }.distinctUntilChanged()

    /** Whether there is a download the connection would let run now. */
    val hasPendingWork: kotlinx.coroutines.flow.Flow<Boolean> =
        kotlinx.coroutines.flow.combine(store.index, reachability.online) { index, online ->
            online && index.items.values.any { it.state in OfflineDownloader.Runnable }
        }

    /** Signed in as a Kids profile: downloads its server would refuse it stay out of reach. */
    var kidsProfile: Boolean = false
        private set

    fun attach(api: KobblerApi, kids: Boolean = false) {
        kidsProfile = kids
        if (_api.value === api) return
        _api.value = api
        reachability.attach { api.isReachable() }
        scope.launch { replayWrites() }
        maintenance.start(api)
    }

    suspend fun media(key: String, path: String): OfflineMedia? {
        val item = store.item(key) ?: return null
        if (item.file(path) == null) return null
        return mediaLock.withLock {
            media.getOrPut("$key/$path") { OfflineMedia(store, key, path, upstreamFor(item, path)) }
        }
    }

    private fun upstreamFor(item: OfflineItem, path: String): UpstreamSource = when (item.kind) {
        OfflineKind.Movie, OfflineKind.Episode -> UpstreamSource { refresh -> videoUpstream(item.key, refresh) }
        OfflineKind.Audiobook -> UpstreamSource {
            val api = _api.value ?: return@UpstreamSource null
            Upstream(api.audioStreamUrl(item.mediaId, path.removePrefix(AudioDir)), api.authHeaders)
        }
        OfflineKind.Book -> UpstreamSource {
            val api = _api.value ?: return@UpstreamSource null
            Upstream(api.bookFileUrl(item.mediaId, item.bookFormat), api.authHeaders)
        }
    }

    private suspend fun videoUpstream(key: String, refresh: Boolean): Upstream? {
        val item = store.item(key) ?: return null
        if (item.exportQuality != null) return exportUpstream(item, refresh)
        if (!refresh) mediaLock.withLock { directUrls[key] }?.let { return Upstream(it) }
        if (_api.value == null) return null
        openDownloadSession(item)
        return mediaLock.withLock { directUrls[key] }?.let(::Upstream)
            ?: throw UpstreamUnavailable("The server can only stream ${item.title}, not send its file")
    }

    /**
     * The server's copy of the title at the download's quality, waited for until it exists:
     * the server makes it as a job, and only a finished file can be read by range.
     */
    private suspend fun exportUpstream(item: OfflineItem, refresh: Boolean): Upstream? {
        val api = _api.value ?: return null
        val quality = item.exportQuality ?: return null
        val type = mediaTypeOf(item.kind)
        suspend fun create() = api.createOfflineExport(type, item.mediaId, quality)
            ?: throw UpstreamUnavailable("The server has no file for ${item.title}")
        suspend fun original(): Upstream? {
            val playable = DownloadQuality.Original.wire.takeIf { item.session?.method != DirectPlay }
            if (playable == quality) throw UpstreamUnavailable("The server would not make a playable copy of ${item.title}")
            store.updateItem(item.key) { it.copy(exportQuality = playable, exportId = null) }
            return videoUpstream(item.key, refresh)
        }
        var export = (if (refresh) null else item.exportId?.let { api.offlineExport(it) }) ?: create()
        if (export.original) return original()
        adoptSource(item.key, "export:${export.exportId}")
        store.updateItem(item.key) { it.copy(exportId = export.exportId) }
        var lastProgress = -1.0
        var quietSince = now()
        while (!export.ready) {
            if (export.failed) throw UpstreamUnavailable("The server could not make a ${quality} copy of ${item.title}: ${export.error ?: "unknown error"}")
            store.updateItem(item.key, progressOnly = true) { it.copy(serverProgress = export.progress.toFloat().coerceIn(0f, 1f)) }
            delay(ExportPollMs)
            if (export.progress != lastProgress) {
                lastProgress = export.progress
                quietSince = now()
            }
            // Asking again is what gets a job the server lost sent back out.
            val quiet = now() - quietSince > ExportQuietMs
            export = (if (quiet) null else api.offlineExport(export.exportId)) ?: create()
            if (quiet) quietSince = now()
            if (export.original) return original()
            adoptSource(item.key, "export:${export.exportId}")
        }
        store.updateItem(item.key) { it.copy(exportId = export.exportId, exportReady = true, serverProgress = null) }
        return Upstream(export.fileUrl, api.authHeaders)
    }

    /** A session for the title's tracks, subtitles and thumbnails; its own file is used only when it is sent as is. */
    internal suspend fun openDownloadSession(item: OfflineItem): KobblerApi.StreamSession {
        val api = _api.value ?: throw UpstreamUnavailable("Not signed in")
        val session = api.createDownloadSession(mediaTypeOf(item.kind), item.mediaId)
            ?: throw UpstreamUnavailable("The server has no file for ${item.title}")
        val url = if (session.method == DirectPlay && adoptSource(item.key, session.fileId?.let { "file:$it" })) api.playerUrl(session.url) else null
        mediaLock.withLock {
            url?.let { directUrls[item.key] = it }
            downloadSessions.put(item.key, session.sessionId)?.let { old -> scope.launch { api.closeSession(old) } }
        }
        return session
    }

    /** Read by the player in the last few minutes: removing it now would cut playback off. */
    suspend fun keysInUse(): Set<String> {
        val cutoff = now() - InUseMs
        return mediaLock.withLock { media.values.filter { it.lastPlayerReadMs > cutoff }.map { it.key }.toSet() }
    }

    internal suspend fun releaseDownloadSession(key: String) {
        val id = mediaLock.withLock {
            directUrls.remove(key)
            downloadSessions.remove(key)
        } ?: return
        _api.value?.closeSession(id)
    }

    // -- What the viewer asks for --

    fun enqueue(item: OfflineItem) {
        if (store.item(item.key) != null) return
        store.update { it.copy(items = it.items + (item.key to item.copy(addedAtMs = now(), state = OfflineState.Queued))) }
    }

    fun retry(key: String) = store.updateItem(key) { it.copy(state = OfflineState.Queued, error = null) }

    fun setKeep(key: String, keep: Boolean) = store.updateItem(key) { it.copy(keep = keep) }

    suspend fun remove(key: String) {
        downloader.cancel(key)
        mediaLock.withLock { media.keys.filter { it.startsWith("$key/") }.forEach { media.remove(it) } }
        releaseDownloadSession(key)
        store.remove(key)
    }

    fun subscribe(subscription: ShowSubscription) =
        store.update { it.copy(subscriptions = it.subscriptions + (subscription.showId to subscription)) }

    fun unsubscribe(showId: String) = store.update { it.copy(subscriptions = it.subscriptions - showId) }

    // -- OfflineHooks --

    override val isOffline: Boolean get() = !reachability.online.value

    override fun cachedResponse(path: String): String? = store.response(path)

    override suspend fun onResponse(path: String, body: String) {
        reachability.reportSuccess()
        val capture = currentCoroutineContext()[OfflineCapture]
        if (capture != null) store.putResponse(path, capture.owner, body) else store.refreshResponse(path, body)
    }

    override fun onNetworkFailure() = reachability.reportFailure()

    override fun queueWrite(owner: String, method: String, path: String, body: String): Boolean {
        if (!isQueueableWrite(path)) return false
        store.update { it.copy(pendingWrites = it.pendingWrites.enqueue(PendingWrite(method, path, body, owner))) }
        return true
    }

    override fun onWrite(path: String, body: String) {
        val key = finishedBy(path, body) { store.item(it)?.durationSeconds } ?: return
        if (store.item(key)?.watched == false) store.updateItem(key) { it.copy(watched = true) }
    }

    override fun pendingResumeSeconds(owner: String, watchStatePath: String): Int? =
        store.index.value.pendingWrites.resumeSecondsFor(owner, watchStatePath)

    override fun pendingWriteBody(owner: String, path: String): String? =
        store.index.value.pendingWrites.lastOrNull { it.owner == owner && it.path == path }?.body

    private val replayLock = Mutex()

    private suspend fun replayWrites() = replayLock.withLock {
        val api = _api.value ?: return@withLock
        // Another profile's progress waits for that profile: sent with this one's token it
        // would land on the wrong account.
        for (write in store.index.value.pendingWrites.filter { it.owner == api.offlineOwner }) {
            try {
                api.replayWrite(write.method, write.path, write.body)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (_: ApiException) {
                // The server refused it outright; sending it again would be refused again.
            } catch (_: Exception) {
                return@withLock
            }
            store.update { idx -> idx.copy(pendingWrites = idx.pendingWrites - write) }
        }
    }

    override suspend fun playbackSession(
        mediaType: String,
        mediaId: String,
        mint: suspend () -> KobblerApi.StreamSession?,
    ): KobblerApi.StreamSession? {
        val kind = if (mediaType == "movie") OfflineKind.Movie else OfflineKind.Episode
        val item = store.item(kind, mediaId) ?: return null
        val port = port ?: return null
        if (kidsProfile && !item.kidsAllowed) return null
        if (item.exportQuality != null) {
            // Until the server has made the copy there is nothing to read it from, so the
            // title streams as it would undownloaded.
            if (!item.exportReady) return null
            return item.session?.let { localize(it, item, port).copy(sessionId = "$OfflineSessionPrefix${item.key}") }
        }
        if (!isOffline) {
            val fresh = try {
                mint() ?: return null
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (_: ApiException) {
                // The server answered and said no; what it refuses online is not played from disk.
                return null
            } catch (_: Exception) {
                null
            }
            if (fresh != null && fresh.method == DirectPlay) {
                if (!adoptSource(item.key, fresh.fileId?.let { "file:$it" })) return null
                val api = _api.value
                mediaLock.withLock { directUrls[item.key] = api?.playerUrl(fresh.url) ?: fresh.url }
                return localize(fresh, item, port)
            }
            if (fresh != null) _api.value?.let { api -> scope.launch { api.closeSession(fresh.sessionId) } }
        }
        val stored = item.session ?: return null
        return localize(stored, item, port).copy(sessionId = "$OfflineSessionPrefix${item.key}")
    }

    /**
     * Pins the download to [sourceId]. Bytes already kept from a different source — the
     * server replaced the file, or remade its copy — are thrown away rather than mixed in.
     * False when the source cannot be told apart, which is the cue not to tee from it.
     */
    internal fun adoptSource(key: String, sourceId: String?): Boolean {
        val item = store.item(key) ?: return false
        // Nothing to tell sources apart by; OfflineMedia still refuses bytes of a different size.
        if (sourceId == null || item.sourceId == sourceId) return true
        store.updateItem(key) { current ->
            current.copy(
                sourceId = sourceId,
                files = if (current.sourceId == null) current.files else current.files.map { OfflineFile(it.path) },
                state = if (current.sourceId == null || current.state != OfflineState.Complete) current.state else OfflineState.Queued,
                completedAtMs = if (current.sourceId == null) current.completedAtMs else null,
            )
        }
        return true
    }

    /**
     * A finished file goes to the player as a file: no proxy hop, and libVLC keeps no
     * network read-ahead for a source it can seek itself. Only a download still being
     * filled has to come through the proxy, which is what writes down what the player reads.
     */
    private fun mediaUrl(item: OfflineItem, path: String, port: Int): String =
        if (item.file(path)?.complete == true) fileUri(store.absolutePath(item.key, path)) else offlineUrl(port, item.key, path)

    private fun localize(session: KobblerApi.StreamSession, item: OfflineItem, port: Int) = session.copy(
        url = mediaUrl(item, MediaFilePath, port),
        subtitles = session.subtitles.map { sub ->
            val local = subtitlePath(sub)
            if (local != null && local in item.extras) sub.copy(url = offlineUrl(port, item.key, local)) else sub
        },
        thumbnailsUrl = if (ThumbnailsVttPath in item.extras) offlineUrl(port, item.key, ThumbnailsVttPath) else session.thumbnailsUrl,
    )

    override fun servesLocally(url: String): Boolean =
        url.startsWith("file://") || port != null && url.startsWith(offlineBase(port))

    override fun isLocalSession(sessionId: String): Boolean = sessionId.startsWith(OfflineSessionPrefix)

    override fun audioUrl(bookId: String, fileId: String): String? {
        val item = store.item(OfflineKind.Audiobook, bookId) ?: return null
        val path = "$AudioDir$fileId"
        if (item.file(path) == null) return null
        return mediaUrl(item, path, port ?: return null)
    }

    override fun bookFilePath(bookId: String, formats: List<String>): String? {
        val item = store.item(OfflineKind.Book, bookId) ?: return null
        if (formats.none { it.equals(item.bookFormat, ignoreCase = true) }) return null
        val file = item.files.firstOrNull()?.takeIf { it.complete } ?: return null
        return store.absolutePath(item.key, file.path)
    }

    companion object {
        const val DirectPlay = "direct_play"
        const val OfflineSessionPrefix = "offline-"
        const val AudioDir = "audio/"
        private const val InUseMs = 10 * 60 * 1000L
        private const val ExportPollMs = 3_000L
        private const val ExportQuietMs = 10 * 60 * 1000L

        fun mediaTypeOf(kind: OfflineKind) = if (kind == OfflineKind.Movie) "movie" else "episode"
    }
}

internal fun subtitlePath(sub: dev.kobbler.app.data.PlaybackSubtitleTrackDto): String? {
    val url = sub.url ?: return null
    val ext = (sub.format ?: url.substringAfterLast('.', "vtt")).lowercase().take(4)
    return "subs/${stableHash(sub.trackId ?: sub.streamIndex?.toString() ?: url)}.$ext"
}
