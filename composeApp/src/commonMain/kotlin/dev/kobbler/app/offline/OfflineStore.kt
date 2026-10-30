package dev.kobbler.app.offline

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

class OfflineStore(private val fs: OfflineFs, scope: CoroutineScope) {
    private val _index = MutableStateFlow(load())
    val index: StateFlow<OfflineIndex> = _index.asStateFlow()
    private val dirty = Channel<Unit>(Channel.CONFLATED)
    private val files = mutableMapOf<String, RandomFile>()

    init {
        scope.launch {
            for (signal in dirty) {
                persist()
                delay(PersistSpacingMs)
            }
        }
    }

    private fun load(): OfflineIndex =
        fs.read(IndexPath)?.let { runCatching { StoreJson.decodeFromString<OfflineIndex>(it.decodeToString()) }.getOrNull() }
            ?: OfflineIndex()

    private fun persist() = fs.write(IndexPath, StoreJson.encodeToString(OfflineIndex.serializer(), _index.value).encodeToByteArray())

    fun update(transform: (OfflineIndex) -> OfflineIndex) {
        _index.update(transform)
        dirty.trySend(Unit)
    }

    fun updateItem(key: String, transform: (OfflineItem) -> OfflineItem) = update { idx ->
        val item = idx.items[key] ?: return@update idx
        idx.copy(items = idx.items + (key to transform(item)))
    }

    fun updateFile(key: String, path: String, transform: (OfflineFile) -> OfflineFile) = updateItem(key) { item ->
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
        dirty.trySend(Unit)
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
        const val PersistSpacingMs = 1_000L
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
