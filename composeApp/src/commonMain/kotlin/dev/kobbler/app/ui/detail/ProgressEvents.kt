package dev.kobbler.app.ui.detail

import dev.kobbler.app.data.LibraryEventFrame
import dev.kobbler.app.data.PlaybackClient
import dev.kobbler.app.data.idField
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

// A save from this device is already on the page through the player's mirror, and the
// server echoes one every few seconds of playback; refetching for those would redraw the
// page under the player all the way through. Finishing is the exception: the server's
// next-up is the one worth having.
internal fun refreshesForProgress(frame: LibraryEventFrame, scopeId: String?): Boolean {
    if (frame.kind != "watch_progress") return false
    if (scopeId != null && frame.idField("scope_id") != scopeId) return false
    if (frame.idField("client_id") != PlaybackClient.id) return true
    return (frame.data["watched"] as? JsonPrimitive)?.booleanOrNull == true
}
