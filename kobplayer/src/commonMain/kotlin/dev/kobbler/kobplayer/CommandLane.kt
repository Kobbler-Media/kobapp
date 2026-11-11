package dev.kobbler.kobplayer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.TimeSource

// A stop or seek answers in well under a second; one that has not after this is stuck in
// libVLC, and whatever is waiting behind it moves to a fresh player.
internal const val WedgedVlcCallMs = 1_500L

// An escaped player whose call has not returned by now likely never will: it, and whatever
// it holds, stays until the process ends.
internal const val StuckReleaseWaitMs = 30_000L

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
 *
 * An escaped engine is asked to [Engine.unstick], and each escape is reported in [stalls]:
 * once when it happens, and again once the engine is retired or after [releaseWaitMs] without.
 * Closing behind a stuck call asks it to come unstuck too, so it can be retired.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class CommandLane<T>(
    private val scope: CoroutineScope,
    private val wedgedAfterMs: Long,
    private val newEngine: () -> Engine<T>,
    private val onReplaced: suspend (old: T, new: T) -> Unit,
    private val resumeTitle: T.() -> Unit = {},
    private val releaseWaitMs: Long = StuckReleaseWaitMs,
) {
    class Engine<T>(
        val target: T,
        val dispatcher: CoroutineDispatcher,
        val stuckIn: () -> String = { "" },
        val unstick: () -> Unit = {},
        val retire: (T) -> Unit,
    )

    private class Command<T>(
        val generation: Int,
        val block: T.() -> Unit,
        val dropped: () -> Unit,
        val holdsUpTitle: Boolean = false,
    )

    @kotlin.concurrent.Volatile private var engine = newEngine()
    val current: T get() = engine.target

    private val stallReports = MutableSharedFlow<PlayerStall>(extraBufferCapacity = 8)
    val stalls: Flow<PlayerStall> = stallReports.asSharedFlow()

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
        var askedToUnstick = false
        while (withTimeoutOrNull(WatchSliceMs) { job.join() } == null) {
            val stuckForMs = started.elapsedNow().inWholeMilliseconds
            if (stuckForMs < wedgedAfterMs) continue
            val titleWaits = command.holdsUpTitle || generation.load() > command.generation
            if (titleWaits || (!closed && waiting.load() > 0)) {
                replace(running, stuckForMs)
                if (!titleWaits) run(Command(command.generation, resumeTitle, dropped = {}))
                return
            }
            if (closed && !askedToUnstick) {
                askedToUnstick = true
                running.unstick()
            }
        }
    }

    private suspend fun replace(stuck: Engine<T>, stuckForMs: Long) {
        val stall = PlayerStall(stuckForMs, stuck.stuckIn())
        val fresh = newEngine()
        engine = fresh
        onReplaced(stuck.target, fresh.target)
        report(stall)
        stuck.unstick()
        // Queued behind the call it is stuck in: runs if that ever returns.
        val retired = scope.launch(stuck.dispatcher) { stuck.retire(stuck.target) }
        scope.launch {
            val released = withTimeoutOrNull(releaseWaitMs) { retired.join() } != null
            report(stall.copy(released = released))
        }
    }

    private fun report(stall: PlayerStall) {
        println("[kobplayer] ${stall.summary()}")
        stallReports.tryEmit(stall)
    }

    private companion object {
        const val WatchSliceMs = 100L
    }
}
