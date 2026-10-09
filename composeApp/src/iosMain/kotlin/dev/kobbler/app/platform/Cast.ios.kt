package dev.kobbler.app.platform

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** No cast sender on iOS — the Google Cast iOS SDK is not wired up. */
actual fun castManager(): CastSender = CastManager()

class CastManager : CastSender {
    override val device: StateFlow<CastDevice?> = MutableStateFlow(null).asStateFlow()
    override val events: SharedFlow<CastEvent> = MutableSharedFlow<CastEvent>().asSharedFlow()

    override fun start() {}
    override suspend fun load(load: CastLoad) {}
    override suspend fun send(type: String, payloadJson: String) {}
    override suspend fun seekTo(positionSeconds: Int) {}
    override suspend fun togglePlayPause() {}
    override suspend fun stop() {}
}

actual fun saveCastAppId(appId: String?) {}

@Composable
actual fun CastButton() {}
