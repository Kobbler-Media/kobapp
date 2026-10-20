package dev.kobbler.kobplayer

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
class LatestWins<T : Any>(
    private val post: (() -> Unit) -> Boolean,
    private val run: (T) -> Unit,
) {
    private val pending = AtomicReference<T?>(null)

    fun offer(value: T) {
        if (pending.exchange(value) != null) return
        if (!post { pending.exchange(null)?.let(run) }) pending.store(null)
    }

    fun clear() {
        pending.store(null)
    }
}
