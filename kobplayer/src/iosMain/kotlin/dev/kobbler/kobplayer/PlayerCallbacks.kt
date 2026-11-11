@file:OptIn(ExperimentalForeignApi::class)

package dev.kobbler.kobplayer

import cnames.structs.libvlc_media_t
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import libvlc.*

private fun events(opaque: COpaquePointer?) = opaque!!.asStableRef<IosPlayer.Events>().get()

// Called on libVLC's own threads, with the player locked: nothing here may call back into it.
internal val playerCallbacks = nativeHeap.alloc<libvlc_media_player_cbs> {
    version = 0u
    on_state_changed = staticCFunction { opaque: COpaquePointer?, state: libvlc_state_t ->
        events(opaque).stateChanged(vlcInputState(state))
    }
    on_media_stopping = staticCFunction { opaque: COpaquePointer?, _: CPointer<libvlc_media_t>?, reason: libvlc_stopping_reason_t ->
        events(opaque).stopping(error = reason == libvlc_stopping_reason_error)
    }
    on_buffering_changed = staticCFunction { opaque: COpaquePointer?, buffering: Float ->
        events(opaque).buffering(buffering)
    }
    on_capabilities_changed = staticCFunction { opaque: COpaquePointer?, _: libvlc_capability_t, caps: libvlc_capability_t ->
        events(opaque).pausable(caps and libvlc_capability_pause == libvlc_capability_pause)
    }
    on_position_changed = staticCFunction { opaque: COpaquePointer?, time: Long, _: Double ->
        events(opaque).position(time)
    }
    on_length_changed = staticCFunction { opaque: COpaquePointer?, length: Long ->
        events(opaque).length(length)
    }
    on_track_list_changed = staticCFunction { opaque: COpaquePointer?, action: libvlc_list_action_t, _: libvlc_track_type_t, _: CPointer<ByteVar>? ->
        if (action != libvlc_list_action_removed) events(opaque).tracksChanged()
    }
    on_track_selection_changed = staticCFunction { opaque: COpaquePointer?, _: libvlc_track_type_t, _: CPointer<ByteVar>?, _: CPointer<ByteVar>? ->
        events(opaque).tracksChanged()
    }
    on_vout_changed = staticCFunction { opaque: COpaquePointer?, _: UInt ->
        events(opaque).tracksChanged()
    }
}.ptr
