package dev.kobbler.app.offline

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

class OfflineStore(private val fs: OfflineFs, scope: CoroutineScope) {
    private val _index = MutableStateFlow(load())
    val index: StateFlow<OfflineIndex> = _index.asStateFlow()
    // Two kinds of change: one the index must not lose (a download added, finished,
    // removed) and progress, which at worst is fetched again. Rewriting the whole index for
    // every megabyte was most of a download's CPU on a TV.
    private val urgent = Channel<Unit>(Channel.CONFLATED)
    private val lazy = Channel<Unit>(Channel.CONFLATED)
    private val persistLock = Mutex()
    private val files = mutableMapOf<String, RandomFile>()

    init {
        scope.launch {
            while (true) {
                select {
                    urgent.onReceive {}
                    lazy.onReceive { withTimeoutOrNull(ProgressPersistMs) { urgent.receive() } }
                }
                urgent.tryReceive()
                lazy.tryReceive()
                persist()
                delay(PersistSpacingMs)
            }
        }
    }

    private fun load(): OfflineIndex =
        fs.read(IndexPath)?.let { runCatching { StoreJson.decodeFromString<OfflineIndex>(it.decodeToString()) }.getOrNull() }
            ?: OfflineIndex()

    private suspend fun persist() = persistLock.withLock {
        fs.write(IndexPath, StoreJson.encodeToString(OfflineIndex.serializer(), _index.value).encodeToByteArray())
    }

    fun update(progressOnly: Boolean = false, transform: (OfflineIndex) -> OfflineIndex) {
        _index.update(transform)
        (if (progressOnly) lazy else urgent).trySend(Unit)
    }

    fun updateItem(key: String, progressOnly: Boolean = false, transform: (OfflineItem) -> OfflineItem) = update(progressOnly) { idx ->
        val item = idx.items[key] ?: return@update idx
        idx.copy(items = idx.items + (key to transform(item)))
    }

    fun updateFile(key: String, path: String, progressOnly: Boolean = false, transform: (OfflineFile) -> OfflineFile) =
        updateItem(key, progressOnly) { item ->
            item.copy(files = item.files.map { if (it.path == path) transform(it) else it })
        }

    fun item(key: String): OfflineItem? = _index.value.items[key]

    fun item(kind: OfflineKind, mediaId: String): OfflineItem? = item(OfflineItem.keyOf(kind, mediaId))

    fun filePath(key: String, path: String) = "$ItemsDir/$key/$path"

    fun absolutePath(key: String, path: String) = fs.absolute(filePath(key, path))

    suspend fun randomFile(key: String, path: String): RandomFile = filesLock.withLock {
        files.getOrPut(filePath(key, path)) { fs.open(filePath(key, path)) }
    }

    /** Lets go of an item's open files once nothing is reading them, so a long audiobook holds no descriptors. */
    suspend fun closeFiles(key: String) = filesLock.withLock {
        files.keys.filter { it.startsWith("$ItemsDir/$key/") }.forEach { files.remove(it)?.close() }
    }

    fun writeWhole(key: String, path: String, bytes: ByteArray) = fs.write(filePath(key, path), bytes)

    fun readWhole(key: String, path: String): ByteArray? = fs.read(filePath(key, path))

    fun response(path: String): String? =
        if (path in _index.value.responses) fs.read(responsePath(path))?.decodeToString() else null

    fun putResponse(path: String, owner: String, body: String) {
        fs.write(responsePath(path), body.encodeToByteArray())
        update { it.copy(responses = it.responses.addOwner(path, owner)) }
    }

    /** Keeps an already-pinned response current; anything not pinned is ignored. */
    fun refreshResponse(path: String, body: String) {
        if (path in _index.value.responses) fs.write(responsePath(path), body.encodeToByteArray())
    }

    fun imageBytes(url: String): ByteArray? = if (url in _index.value.images) fs.read(imagePath(url)) else null

    fun hasImage(url: String) = url in _index.value.images

    fun putImage(url: String, owner: String, bytes: ByteArray) {
        fs.write(imagePath(url), bytes)
        update { it.copy(images = it.images.addOwner(url, owner)) }
    }

    fun addImageOwner(url: String, owner: String) = update { it.copy(images = it.images.addOwner(url, owner)) }

    suspend fun remove(key: String) {
        closeFiles(key)
        val before = _index.value
        update { idx ->
            idx.copy(
                items = idx.items - key,
                responses = idx.responses.dropOwner(key),
                images = idx.images.dropOwner(key),
            )
        }
        // Written before anything is deleted: an index that outlives its files would claim bytes
        // that are gone.
        persist()
        fs.delete("$ItemsDir/$key")
        (before.responses.keys - _index.value.responses.keys).forEach { fs.delete(responsePath(it)) }
        (before.images.keys - _index.value.images.keys).forEach { fs.delete(imagePath(it)) }
    }

    fun usedBytes() = fs.usedBytes()

    fun freeBytes() = fs.freeBytes()

    private val filesLock = Mutex()

    private fun responsePath(path: String) = "$ResponsesDir/${stableHash(path)}.json"

    private fun imagePath(url: String) = "$ImagesDir/${stableHash(url)}"

    private companion object {
        const val IndexPath = "index.json"
        const val ItemsDir = "items"
        const val ResponsesDir = "responses"
        const val ImagesDir = "images"
        const val PersistSpacingMs = 250L
        const val ProgressPersistMs = 5_000L
    }
}

private fun Map<String, Set<String>>.addOwner(key: String, owner: String) = this + (key to (this[key].orEmpty() + owner))

private fun Map<String, Set<String>>.dropOwner(owner: String) =
    mapValues { it.value - owner }.filterValues { it.isNotEmpty() }

internal val StoreJson = Json { ignoreUnknownKeys = true; encodeDefaults = false }

/** FNV-1a: stable across launches and platforms, unlike [String.hashCode] on a path a server may grow. */
fun stableHash(text: String): String {
    var hash = -0x340d631b7bdddcdbL
    for (byte in text.encodeToByteArray()) {
        hash = hash xor (byte.toLong() and 0xff)
        hash *= 0x100000001b3L
    }
    return hash.toULong().toString(16)
}
