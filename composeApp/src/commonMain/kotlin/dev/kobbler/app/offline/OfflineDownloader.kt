package dev.kobbler.app.offline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Works through the queue one title at a time, oldest first, and waits out a lost connection. */
class OfflineDownloader(private val engine: OfflineEngine, private val scope: CoroutineScope) {
    private val _active = MutableStateFlow<String?>(null)
    val active: StateFlow<String?> = _active.asStateFlow()
    private var job: Job? = null
    private var loop: Job? = null

    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (isActive) {
                val ready = combine(engine.store.index, engine.reachability.online, engine.api) { idx, online, api ->
                    if (!online || api == null) null else idx.items.values.filter { it.state in Runnable }.minByOrNull { it.addedAtMs }
                }.first { it != null }!!
                _active.value = ready.key
                job = launch { run(ready.key) }
                job?.join()
                _active.value = null
            }
        }
    }

    fun cancel(key: String) {
        if (_active.value == key) job?.cancel()
    }

    private suspend fun run(key: String) {
        val store = engine.store
        store.updateItem(key) { it.copy(state = OfflineState.Downloading, error = null) }
        try {
            if (store.item(key)?.prepared == false) prepare(engine, key)
            val item = store.item(key) ?: return
            for (file in item.files) fill(key, file.path)
            store.updateItem(key) { it.copy(state = OfflineState.Complete, completedAtMs = engine.now()) }
            engine.releaseDownloadSession(key)
            if (key !in engine.keysInUse()) store.closeFiles(key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SourceChanged) {
            store.updateItem(key) { it.copy(state = OfflineState.Queued, completedAtMs = null) }
        } catch (e: Exception) {
            val online = engine.reachability.check()
            store.updateItem(key) {
                if (online) it.copy(state = OfflineState.Failed, error = e.message ?: "Download failed")
                else it.copy(state = OfflineState.Waiting)
            }
        }
    }

    private suspend fun fill(key: String, path: String) {
        val media = engine.media(key, path) ?: return
        val size = media.size()
        while (true) {
            val ranges = engine.store.item(key)?.file(path)?.ranges ?: return
            val gap = ranges.firstGap(media.playhead, size) ?: return
            val end = minOf(gap.last + 1, gap.first + FillChunkBytes)
            media.stream(gap.first, end, fromPlayer = false) { _, _ -> }
        }
    }

    internal companion object {
        val Runnable = setOf(OfflineState.Queued, OfflineState.Downloading, OfflineState.Waiting)
        const val FillChunkBytes = 32L * 1024 * 1024
    }
}
