package dev.kobbler.app.offline

import dev.kobbler.app.data.KobblerApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import dev.kobbler.app.data.AppForeground
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The housekeeping downloads need without the viewer asking: new episodes of followed shows,
 * watched state learned from other devices, and the cleanup policy.
 */
class OfflineMaintenance(private val engine: OfflineEngine, private val scope: CoroutineScope) {
    private val lock = Mutex()
    private var jobs: List<Job> = emptyList()

    fun start(api: KobblerApi) {
        jobs.forEach { it.cancel() }
        jobs = listOf(
            scope.launch {
                while (true) {
                    runOnce()
                    delay(SweepMs)
                }
            },
            scope.launch {
                AppForeground.state.drop(1).filter { it }.collect { checkSubscriptions() }
            },
            scope.launch {
                api.libraryEvents()
                    .filter { it.kind.startsWith("tv_") == true }
                    .conflate()
                    .collect {
                        delay(EventSettleMs)
                        if (engine.store.index.value.subscriptions.isNotEmpty()) checkSubscriptions()
                    }
            },
        )
    }

    suspend fun runOnce() = lock.withLock {
        if (!engine.isOffline) {
            checkSubscriptions()
            refreshWatched()
        }
        cleanUp()
    }

    suspend fun checkSubscriptions() {
        val api = engine.api.value ?: return
        for (subscription in engine.store.index.value.subscriptions.values) {
            val show = quietly { api.getShow(subscription.showId) } ?: continue
            val fresh = newEpisodes(show, subscription)
            if (fresh.isEmpty()) continue
            engine.downloadEpisodes(show, fresh)
            engine.subscribe(subscription.copy(knownEpisodeIds = subscription.knownEpisodeIds + fresh.mapNotNull { it.id }))
        }
    }

    private suspend fun refreshWatched() {
        val api = engine.api.value ?: return
        val video = engine.store.index.value.items.values.filter { !it.watched && it.kind in VideoKinds }
        for (item in video) {
            if (quietly { api.isWatched(OfflineEngine.mediaTypeOf(item.kind), item.mediaId) } == true) {
                engine.store.updateItem(item.key) { it.copy(watched = true) }
            }
        }
    }

    private suspend fun cleanUp() {
        val busy = engine.keysInUse()
        val due = dueForCleanup(engine.store.index.value.items.values, CleanupPolicy.current, engine.now()) { it in busy }
        due.forEach { engine.remove(it.key) }
    }

    private suspend fun <T> quietly(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val SweepMs = 60 * 60 * 1000L
        const val EventSettleMs = 30_000L
        val VideoKinds = setOf(OfflineKind.Movie, OfflineKind.Episode)
    }
}
