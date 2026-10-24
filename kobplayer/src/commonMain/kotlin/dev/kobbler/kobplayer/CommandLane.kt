package dev.kobbler.kobplayer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.TimeSource

/**
 * The one thread a native player is driven from, made so that nothing can hold it.
 *
 * Commands run in the order given. [supersede] is for a command that replaces the
 * title — a load or a stop: whatever was queued for the old one and has not started is
 * dropped, and if the command running ahead of it has been running for [wedgedAfterMs],
 * the native player is presumed stuck in it and abandoned. A fresh engine takes over,
 * and the abandoned one is retired whenever its call does return.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class CommandLane<T>(
    private val scope: CoroutineScope,
    private val wedgedAfterMs: Long,
    private val newEngine: () -> Engine<T>,
    private val onReplaced: suspend (old: T, new: T) -> Unit,
) {
    class Engine<T>(val target: T, val dispatcher: CoroutineDispatcher, val retire: (T) -> Unit)

    private class Command<T>(val generation: Int, val block: T.() -> Unit, val dropped: () -> Unit)

    @kotlin.concurrent.Volatile private var engine = newEngine()
    val current: T get() = engine.target

    private val queue = Channel<Command<T>>(Channel.UNLIMITED)
    private val generation = AtomicInt(0)
    @kotlin.concurrent.Volatile private var closed = false

    init {
        scope.launch {
            for (command in queue) {
                if (command.generation < generation.load()) command.dropped() else run(command)
            }
        }
    }

    fun post(block: T.() -> Unit): Boolean = send(generation.load(), block, dropped = {})

    fun supersede(block: T.() -> Unit): Boolean = send(generation.incrementAndFetch(), block, dropped = {})

    /** [block]'s answer, or null if it was dropped or the lane is closed. */
    suspend fun <R> call(block: T.() -> R): R? {
        val result = CompletableDeferred<R?>()
        if (!send(generation.load(), { result.complete(block()) }, dropped = { result.complete(null) })) return null
        return result.await()
    }

    /** Retires the engine once everything already given has run, then runs [afterwards]. Returns at once. */
    fun close(afterwards: () -> Unit = {}) {
        send(generation.load(), { engine.retire(this); afterwards() }, dropped = {})
        closed = true
        queue.close()
    }

    private fun send(generation: Int, block: T.() -> Unit, dropped: () -> Unit): Boolean {
        if (closed) return false
        return queue.trySend(Command(generation, block, dropped)).isSuccess.also { if (!it) dropped() }
    }

    private suspend fun run(command: Command<T>) {
        val running = engine
        val job = scope.launch(running.dispatcher) { command.block(running.target) }
        val started = TimeSource.Monotonic.markNow()
        while (withTimeoutOrNull(WatchSliceMs) { job.join() } == null) {
            val waitedOn = generation.load() > command.generation
            if (waitedOn && started.elapsedNow().inWholeMilliseconds >= wedgedAfterMs) {
                replace(running)
                return
            }
        }
    }

    private suspend fun replace(stuck: Engine<T>) {
        val fresh = newEngine()
        engine = fresh
        onReplaced(stuck.target, fresh.target)
        // Queued behind the call it is stuck in: runs if that ever returns.
        scope.launch(stuck.dispatcher) { stuck.retire(stuck.target) }
    }

    private companion object {
        const val WatchSliceMs = 100L
    }
}
