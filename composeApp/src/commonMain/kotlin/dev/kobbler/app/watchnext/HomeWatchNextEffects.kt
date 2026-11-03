package dev.kobbler.app.watchnext

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.kobbler.app.data.AppForeground
import dev.kobbler.app.data.AuthScreen
import dev.kobbler.app.data.AuthState
import dev.kobbler.app.data.ContinueItem
import dev.kobbler.app.data.KobblerApi
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter

// The final position save lands as the player closes, a moment after the app leaves the screen.
private const val LeftAppDelayMs = 5_000L
// Positions are saved every few seconds while playing; one refresh after they stop is enough.
private const val ProgressDelayMs = 20_000L

@Composable
fun HomeWatchNextProfileSync(auth: AuthState) {
    val publisher = HomeWatchNext.publisher
    if (publisher != null) {
        val ready = auth.screen != AuthScreen.Loading
        LaunchedEffect(ready, auth.currentProfileId) { if (ready) publisher.refresh(0) }
        LaunchedEffect(publisher) {
            AppForeground.state.drop(1).filter { !it }.collect { publisher.refresh(LeftAppDelayMs) }
        }
    }
    val launch by HomeWatchNext.launches.collectAsState()
    LaunchedEffect(launch, auth.screen, auth.currentProfileId) {
        val pending = launch ?: return@LaunchedEffect
        when (val step = launchStep(pending, auth.profiles, auth.currentProfileId, auth.screen)) {
            is LaunchStep.SwitchTo -> auth.confirmProfile(step.profileId)
            LaunchStep.Drop -> HomeWatchNext.launches.compareAndSet(pending, null)
            LaunchStep.Play, LaunchStep.Wait -> Unit
        }
    }
}

@Composable
fun HomeWatchNextSession(auth: AuthState, api: KobblerApi?, play: (ContinueItem) -> Unit) {
    val publisher = HomeWatchNext.publisher
    if (publisher != null && api != null) {
        LaunchedEffect(api) {
            api.libraryEvents().filter { it.kind == "watch_progress" }.collect { publisher.refresh(ProgressDelayMs) }
        }
    }
    val launch by HomeWatchNext.launches.collectAsState()
    LaunchedEffect(launch, auth.currentProfileId, api) {
        val pending = launch ?: return@LaunchedEffect
        if (api == null || pending.profileId != auth.currentProfileId) return@LaunchedEffect
        if (HomeWatchNext.launches.compareAndSet(pending, null)) play(pending.toContinueItem())
    }
}
