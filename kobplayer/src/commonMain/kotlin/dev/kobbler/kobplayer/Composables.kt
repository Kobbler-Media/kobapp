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
 * Create a [Player] outside of Compose (for non-UI use like TTS chunk playback).
 * Caller is responsible for calling [Player.release] when done.
 */
expect fun createPlayer(config: PlayerConfig = PlayerConfig(), parentContext: kotlin.coroutines.CoroutineContext = kotlinx.coroutines.Dispatchers.Main): Player

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
