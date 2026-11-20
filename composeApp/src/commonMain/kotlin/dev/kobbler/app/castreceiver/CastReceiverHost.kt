package dev.kobbler.app.castreceiver

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.ui.player.GlobalPlayerState
import dev.kobbler.kobplayer.Player
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

private const val ObserveEveryMs = 500L

@Composable
fun rememberCastReceiver(gps: GlobalPlayerState, player: Player, tvApi: KobblerApi): CastReceiverSession {
    val currentTvApi by rememberUpdatedState(tvApi)
    val session = remember(gps, player) {
        CastReceiverSession(
            playback = GpsReceiverPlayback(gps, player) { currentTvApi },
            send = CastReceiver::send,
            capabilities = { currentTvApi.capabilities() },
            scope = gps.teardownScope,
        )
    }
    LaunchedEffect(session) { CastReceiver.serve(session) }
    LaunchedEffect(session) {
        snapshotFlow { session.isActive }.collectLatest { active ->
            if (!active) {
                CastReceiver.publish(null)
                return@collectLatest
            }
            while (true) {
                val now = session.playbackNow()
                session.observe(now)
                CastReceiver.publish(now.takeIf { session.isActive })
                delay(ObserveEveryMs)
            }
        }
    }
    return session
}
