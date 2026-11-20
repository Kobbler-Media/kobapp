package dev.kobbler.app.platform.dlna

import dev.kobbler.app.platform.CastBackend
import dev.kobbler.app.platform.CastDevice
import dev.kobbler.app.platform.CastEvent
import dev.kobbler.app.platform.CastFeature
import dev.kobbler.app.platform.CastLoad
import dev.kobbler.app.platform.PlaybackProtocol
import dev.kobbler.app.platform.PlaybackTarget
import dev.kobbler.app.platform.PlaybackTargetKind
import io.ktor.client.HttpClient
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val ActiveSearchEvery = 10.seconds

/**
 * DLNA MediaRenderers: found over SSDP, driven over SOAP, with this device running the
 * session the renderer cannot run for itself (see [DlnaPlayback]).
 *
 * [keepAlive] is told the renderer's name while a session runs and null once it ends: the
 * phone is the session's only driver, so it has to stay awake for as long as it plays.
 */
class DlnaBackend internal constructor(
    private val http: HttpClient,
    openTransport: () -> SsdpTransport?,
    foreground: StateFlow<Boolean>,
    private val keepAlive: (rendererName: String?) -> Unit = {},
    private val pollEvery: Duration = 1.seconds,
) : CastBackend {
    override val protocol = PlaybackProtocol.Dlna
    override val features: Set<CastFeature> = emptySet()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val searchNow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val renderers: StateFlow<List<DlnaRenderer>> = foreground
        .flatMapLatest { inFront -> if (inFront) dlnaRenderers(openTransport, http, searchNow) else emptyFlow() }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    override val targets: Flow<List<PlaybackTarget>> = renderers.map { found ->
        found.map { PlaybackTarget(it.udn, it.friendlyName, PlaybackTargetKind.Tv, PlaybackProtocol.Dlna) }
    }

    private val _device = MutableStateFlow<CastDevice?>(null)
    override val device: StateFlow<CastDevice?> = _device.asStateFlow()

    private val _events = MutableSharedFlow<CastEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<CastEvent> = _events.asSharedFlow()

    @Volatile private var playback: DlnaPlayback? = null

    override fun start() {}

    override suspend fun scanActively() {
        while (true) {
            searchNow.tryEmit(Unit)
            delay(ActiveSearchEvery)
        }
    }

    override suspend fun connect(target: PlaybackTarget) {
        val renderer = renderers.value.firstOrNull { it.udn == target.id } ?: return
        playback?.stop()
        _device.value = CastDevice(renderer.friendlyName, connecting = true)
        val sink = renderer.connectionManager?.let { cm ->
            runCatching { http.soap(cm, "GetProtocolInfo")["Sink"] }.getOrNull()
        }
        playback = DlnaPlayback(
            transport = AvTransport(http, renderer.avTransport),
            capabilities = sink?.let(::capabilitiesFromSink) ?: DlnaBaseline,
            http = http,
            scope = scope,
            emit = { _events.emit(it) },
            onStoppedThere = ::stoppedOnRenderer,
            pollEvery = pollEvery,
        )
        _device.value = CastDevice(renderer.friendlyName)
        keepAlive(renderer.friendlyName)
    }

    override suspend fun load(load: CastLoad) {
        playback?.load(load)
    }

    override suspend fun send(type: String, payloadJson: String) {
        playback?.handle(type, payloadJson)
    }

    override suspend fun seekTo(positionSeconds: Int) {
        playback?.seekTo(positionSeconds)
    }

    override suspend fun togglePlayPause() {
        playback?.togglePlayPause()
    }

    override suspend fun stop() {
        playback?.stop()
        release()
    }

    // Called from the playback's own poll, which stopping it cancels: let go of it first.
    private fun stoppedOnRenderer() {
        val ended = playback
        release()
        scope.launch { ended?.stop() }
    }

    private fun release() {
        playback = null
        _device.value = null
        keepAlive(null)
    }
}
