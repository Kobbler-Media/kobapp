package dev.kobbler.kobplayer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Create and remember a [Player] tied to the composition lifecycle.
 * The player is automatically released when the composable leaves the composition.
 *
 * Media is loaded separately via [Player.load]:
 * ```
 * val player = rememberPlayer()
 * LaunchedEffect(url) {
 *     player.load(MediaSource.Url(url, headers))
 *     player.play()
 * }
 * ```
 */
@Composable
expect fun rememberPlayer(config: PlayerConfig = PlayerConfig()): Player

/**
 * Like [rememberPlayer], but builds the player off the main thread and returns null until
 * it exists.
 *
 * Construction is not cheap — on Android it loads the libvlc natives and initialises the
 * VLC core — and the caller is the app scaffold, so doing it inline stalls the first frame
 * after login whether or not anything is ever played. Release is still tied to the
 * composition, exactly as [rememberPlayer] does it.
 */
@Composable
expect fun rememberPlayerAsync(config: PlayerConfig = PlayerConfig()): Player?

/**
 * Makes players on demand, for a screen that only sometimes needs one. Each belongs to the
 * caller, who must [Player.release] it. Taken from a composition because that is the only
 * place Android has the Context a player is built from.
 */
@Composable
expect fun rememberPlayerFactory(): (PlayerConfig) -> Player

/**
 * Render the video surface for a [Player].
 *
 * The [overlay] slot lets you layer Compose UI directly on top of the video —
 * controls, subtitles, debug info, whatever you need. The [Player] is passed
 * to the overlay so you can read state and call commands.
 *
 * ```
 * VideoSurface(player, Modifier.fillMaxSize()) { p ->
 *     // Your controls here — they render on top of the video
 *     PlayerControls(p)
 * }
 * ```
 */
@Composable
expect fun VideoSurface(
    player: Player,
    modifier: Modifier = Modifier,
    overlay: @Composable (Player) -> Unit = {},
)
