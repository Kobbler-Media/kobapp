package dev.kobbler.app.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Every backend this device can hand playback to, behind the one session the app sees. */
class CastRouter(private val backends: List<CastBackend>) : CastSender {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private val active: CastBackend? get() = backends.firstOrNull { it.device.value != null }

    val targets: Flow<List<PlaybackTarget>> =
        if (backends.isEmpty()) flowOf(emptyList())
        else combine(backends.map { it.targets }) { found -> found.flatMap { it } }

    override val device: StateFlow<CastDevice?> =
        if (backends.isEmpty()) MutableStateFlow(null)
        else combine(backends.map { it.device }) { devices -> devices.firstNotNullOfOrNull { it } }
            .stateIn(scope, SharingStarted.Eagerly, active?.device?.value)

    override val events: SharedFlow<CastEvent> =
        merge(*backends.map { it.events }.toTypedArray()).shareIn(scope, SharingStarted.Eagerly)


    override fun start() = backends.forEach { it.start() }

    suspend fun scanActively() = coroutineScope {
        backends.forEach { launch { it.scanActively() } }
    }

    suspend fun connect(target: PlaybackTarget) {
        val backend = backends.first { it.protocol == target.protocol }
        active?.takeIf { it !== backend }?.stop()
        backend.connect(target)
    }

    override suspend fun load(load: CastLoad) { active?.load(load) }
    override suspend fun send(type: String, payloadJson: String) { active?.send(type, payloadJson) }
    override suspend fun seekTo(positionSeconds: Int) { active?.seekTo(positionSeconds) }
    override suspend fun togglePlayPause() { active?.togglePlayPause() }
    override suspend fun stop() { active?.stop() }
}
