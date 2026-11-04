package dev.kobbler.app.ui.player

import dev.kobbler.app.data.ClientCapabilities
import dev.kobbler.app.platform.CastDevice
import dev.kobbler.app.platform.CastEvent
import dev.kobbler.app.platform.CastLoad
import dev.kobbler.app.platform.CastSender
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** A receiver that records what it was told, standing in for one across the room. */
internal class FakeSender(
    private val decodes: ClientCapabilities? = ClientCapabilities(listOf("h264"), listOf("aac"), listOf("mp4")),
) : CastSender {
    private val _device = MutableStateFlow<CastDevice?>(null)
    override val device: StateFlow<CastDevice?> = _device.asStateFlow()
    private val _events = MutableSharedFlow<CastEvent>(replay = 1, extraBufferCapacity = 8)
    override val events: SharedFlow<CastEvent> = _events.asSharedFlow()

    var started = false
    val loads = mutableListOf<CastLoad>()
    val seeks = mutableListOf<Int>()
    var toggles = 0
    var stopped = false
    val sent = mutableListOf<String>()
    val payloads = mutableListOf<Pair<String, String>>()

    override fun start() { started = true }
    override suspend fun load(load: CastLoad) { loads += load }
    override suspend fun send(type: String, payloadJson: String) {
        sent += type
        payloads += type to payloadJson
        if (type == "CAPABILITIES_REQUEST") decodes?.let { _events.emit(CastEvent.Capabilities(it)) }
    }
    override suspend fun seekTo(positionSeconds: Int) { seeks += positionSeconds }
    override suspend fun togglePlayPause() { toggles++ }
    override suspend fun stop() { stopped = true; _device.value = null }

    fun connecting(name: String) { _device.value = CastDevice(name, connecting = true) }
    fun connect(name: String) { _device.value = CastDevice(name) }
    fun disconnect() { _device.value = null }
    suspend fun say(event: CastEvent) { _events.emit(event) }
}
