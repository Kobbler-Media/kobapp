package dev.kobbler.kobplayer

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// No libVLC on iOS until kobvlc builds one; the player is a VlcBinding over it, like Android's and desktop's.
private const val NoLibVlc = "libVLC is not bundled for iOS yet"

@Composable
actual fun rememberPlayer(config: PlayerConfig): Player = error(NoLibVlc)

@Composable
actual fun rememberPlayerFactory(): (PlayerConfig) -> Player = { error(NoLibVlc) }

@Composable
actual fun rememberPlayerAsync(config: PlayerConfig): Player? = null

@Composable
actual fun VideoSurface(player: Player, modifier: Modifier, overlay: @Composable (Player) -> Unit) {
    Box(modifier) { overlay(player) }
}
