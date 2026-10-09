package dev.kobbler.kobplayer

internal const val VlcPicturePool = 3

// VLC holds at most VlcPicturePool pictures, the renderer one front and one pending: with
// two slots beyond the pool, lock() always finds a slot nobody can be reading.
internal class FrameMailbox(private val slotCount: Int) {
    private val heldByVlc = BooleanArray(slotCount)
    private var pending = NONE
    private var front = NONE

    var frontSerial = 0L
        @Synchronized get
        private set

    @Synchronized
    fun lock(): Int {
        val slot = (0 until slotCount).first { !heldByVlc[it] && it != pending && it != front }
        heldByVlc[slot] = true
        return slot
    }

    @Synchronized
    fun unlock(slot: Int) {
        heldByVlc[slot] = false
    }

    @Synchronized
    fun publish(slot: Int) {
        pending = slot
    }

    @Synchronized
    fun acquire(): Int? {
        if (pending != NONE) {
            front = pending
            pending = NONE
            frontSerial++
        }
        return front.takeIf { it != NONE }
    }

    @Synchronized
    fun clear() {
        pending = NONE
        front = NONE
    }

    private companion object {
        const val NONE = -1
    }
}
