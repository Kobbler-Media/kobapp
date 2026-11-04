package dev.kobbler.app.ui.player.audiobook

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.kobbler.app.platform.CastEvent
import dev.kobbler.app.platform.CastSender
import dev.kobbler.kobplayer.PlayerState
import kotlinx.serialization.json.Json

private val castJson = Json { encodeDefaults = true; explicitNulls = true }

/**
 * Move the book between this device and a cast receiver while its screen is open.
 *
 * The screen stays the book's: its transport, chapters and transcript drive and follow
 * the receiver instead of the local player, which waits paused where the cast began. The
 * app-wide video handoff answers what is not the book's — an expired credential, the end
 * of the last file — so this listens only for where the receiver is.
 */
@Composable
fun AudiobookCastHandoff(cast: CastSender, state: AudiobookPlayerState) {
    val device by cast.device.collectAsState()

    LaunchedEffect(device, state.dataLoaded) {
        val target = device
        if (target == null) {
            state.handBackFromCast()
            return@LaunchedEffect
        }
        if (!state.dataLoaded || state.castingTo != null) return@LaunchedEffect
        state.player.pause()
        if (target.connecting) return@LaunchedEffect
        val token = state.api.castToken() ?: return@LaunchedEffect
        cast.send("INIT_AUDIOBOOK", castJson.encodeToString(state.castPayload(token)))
        state.cast = cast
        state.castingTo = target.name
    }

    LaunchedEffect(cast) {
        cast.events.collect { event ->
            if (state.cast == null) return@collect
            when (event) {
                is CastEvent.Position -> {
                    state.currentMs = (event.positionSec * 1000).toLong()
                    state.playerState = if (event.isPlaying) PlayerState.PLAYING else PlayerState.PAUSED
                }
                is CastEvent.AudiobookFileChanged -> {
                    state.currentFileIndex = event.fileIndex
                    state.currentMs = 0
                }
                else -> Unit
            }
        }
    }
}
