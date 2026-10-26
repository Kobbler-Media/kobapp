package dev.kobbler.app.ui.player

import dev.kobbler.app.data.KobblerApi
import dev.kobbler.kobplayer.PlayPauseIntent
import dev.kobbler.kobplayer.Player
import dev.kobbler.kobplayer.playPauseIntent
import kotlinx.coroutines.CoroutineScope

fun GlobalPlayerState.togglePlayback(player: Player, api: KobblerApi, scope: CoroutineScope) {
    if (mediaType != "livetv") return player.togglePlayPause()
    when (playPauseIntent(player.state.value)) {
        PlayPauseIntent.Pause -> pauseLive(this, player, api, scope)
        PlayPauseIntent.Play -> resumeLive(this, player, api, scope)
        PlayPauseIntent.None -> {}
    }
}
