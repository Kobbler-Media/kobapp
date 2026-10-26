package dev.kobbler.kobplayer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.TimeSource

/**
 * The one thread a native player is driven from, made so that nothing can hold it.
 *
 * Commands run in the order given. [supersede] is for a command that replaces the
 * title — a load or a stop: whatever was queued for the old one and has not started is
 * dropped. A command that has been running for [wedgedAfterMs] while anything waits behind
 * it has the native player presumed stuck in it and abandoned. A fresh engine takes over,
 * and the abandoned one is retired whenever its call does return. Unless the next thing
 * waiting is a new title, the fresh engine first runs [resumeTitle] to pick the current one
 * back up, so the commands that were waiting have something to act on.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class CommandLane<T>(
    private val scope: CoroutineScope,
    private val wedgedAfterMs: Long,
    private val newEngine: () -> Engine<T>,
    private val onReplaced: suspend (old: T, new: T) -> Unit,
    private val resumeTitle: T.() -> Unit = {},
) {
    class Engine<T>(val target: T, val dispatcher: CoroutineDispatcher, val retire: (T) -> Unit)

    private class Command<T>(
        val generation: Int,
        val block: T.() -> Unit,
        val dropped: () -> Unit,
        val holdsUpTitle: Boolean = false,
    )

    @kotlin.concurrent.Volatile private var engine = newEngine()
    val current: T get() = engine.target

    private val queue = Channel<Command<T>>(Channel.UNLIMITED)
    private val generation = AtomicInt(0)
    private val waiting = AtomicInt(0)
    @kotlin.concurrent.Volatile private var closed = false

    init {
        scope.launch {
            for (command in queue) {
                waiting.decrementAndFetch()
                if (command.generation < generation.load()) command.dropped() else run(command)
            }
        }
    }

    fun post(block: T.() -> Unit): Boolean = send(generation.load(), block, dropped = {})

    fun supersede(block: T.() -> Unit): Boolean = send(generation.incrementAndFetch(), block, dropped = {})

    /** [supersede] for a load: a [stop] of the old title that gets stuck is escaped like any call a new title waits on. */
    fun replaceTitle(stop: T.() -> Unit, start: T.() -> Unit): Boolean {
        val title = generation.incrementAndFetch()
        return send(title, stop, dropped = {}, holdsUpTitle = true) && send(title, start, dropped = {})
    }

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

    private fun send(generation: Int, block: T.() -> Unit, dropped: () -> Unit, holdsUpTitle: Boolean = false): Boolean {
        if (closed) return false
        waiting.incrementAndFetch()
        return queue.trySend(Command(generation, block, dropped, holdsUpTitle)).isSuccess.also {
            if (!it) {
                waiting.decrementAndFetch()
                dropped()
            }
        }
    }

    private suspend fun run(command: Command<T>) {
        val running = engine
        val job = scope.launch(running.dispatcher) { command.block(running.target) }
        val started = TimeSource.Monotonic.markNow()
        while (withTimeoutOrNull(WatchSliceMs) { job.join() } == null) {
            val titleWaits = command.holdsUpTitle || generation.load() > command.generation
            if ((titleWaits || (!closed && waiting.load() > 0)) && started.elapsedNow().inWholeMilliseconds >= wedgedAfterMs) {
                replace(running)
                if (!titleWaits) run(Command(command.generation, resumeTitle, dropped = {}))
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
