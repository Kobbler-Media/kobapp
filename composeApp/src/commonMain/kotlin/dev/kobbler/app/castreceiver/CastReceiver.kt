package dev.kobbler.app.castreceiver

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/**
 * Where the platform's Cast receiver SDK hands what arrives, and picks up what goes out.
 *
 * Process-wide because the SDK is: a LOAD can arrive with the activity being created, long
 * before the player that will serve it is composed, so the load is held until it is.
 */
object CastReceiver {
    private class PendingLoad(val load: ReceiverLoad, val done: (Boolean) -> Unit)

    private sealed interface Input {
        data class Message(val json: String) : Input
        data class Seek(val absoluteMs: Long) : Input
        data object Play : Input
        data object Pause : Input
        data object Stop : Input
        data object SenderConnected : Input
    }

    private val pending = MutableStateFlow<PendingLoad?>(null)
    private val inputs = MutableSharedFlow<Input>(extraBufferCapacity = 64)
    private val _now = MutableStateFlow<ReceiverNow?>(null)

    val now: StateFlow<ReceiverNow?> = _now.asStateFlow()

    @Volatile
    var isServing: Boolean = false
        private set

    @Volatile
    var outbox: ((String) -> Unit)? = null

    fun load(load: ReceiverLoad, done: (Boolean) -> Unit) {
        pending.value?.done?.invoke(false)
        pending.value = PendingLoad(load, done)
    }

    fun message(json: String) { inputs.tryEmit(Input.Message(json)) }
    fun seek(absoluteMs: Long) { inputs.tryEmit(Input.Seek(absoluteMs)) }
    fun play() { inputs.tryEmit(Input.Play) }
    fun pause() { inputs.tryEmit(Input.Pause) }
    fun stop() { inputs.tryEmit(Input.Stop) }
    fun senderConnected() { inputs.tryEmit(Input.SenderConnected) }

    internal fun publish(now: ReceiverNow?) { _now.value = now }

    internal fun send(json: String) { outbox?.invoke(json) }

    /** Feed [session] until cancelled. One at a time: a second would race the first for the player. */
    internal suspend fun serve(session: CastReceiverSession): Nothing = coroutineScope {
        isServing = true
        coroutineContext.job.invokeOnCompletion { isServing = false }
        launch {
            pending.filterNotNull().collect { p ->
                if (!pending.compareAndSet(p, null)) return@collect
                p.done(runCatching { session.load(p.load) }.getOrDefault(false))
            }
        }
        inputs.collect { input ->
            when (input) {
                is Input.Message -> session.message(input.json)
                is Input.Seek -> session.seek(input.absoluteMs)
                Input.Play -> session.play()
                Input.Pause -> session.pause()
                Input.Stop -> session.stop()
                Input.SenderConnected -> session.senderConnected()
            }
        }
    }
}
