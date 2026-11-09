package dev.kobbler.app.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import dev.kobbler.app.ui.navigation.backInput
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.StreamOption
import dev.kobbler.app.data.StreamSearchResult
import dev.kobbler.app.ui.detail.StreamOptionsOverlay
import dev.kobbler.kobfocus.FocusScopeHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class StreamPicker internal constructor(val onSelect: (StreamOption) -> Unit) {
    var result by mutableStateOf<StreamSearchResult?>(null)
        internal set
}

/** What a picked release is played as while it resolves: the player opens on it at once. */
class PickedRelease(
    val id: String,
    val title: String,
    val subtitle: String?,
    val posterUrl: String?,
)

/**
 * The picker is drawn over everything, the player included: opened from the player's own
 * controls, one drawn by the detail screen underneath stayed out of sight until the player
 * closed.
 */
fun GlobalPlayerState.pickStream(
    scope: CoroutineScope,
    release: PickedRelease,
    search: suspend () -> StreamSearchResult,
    play: suspend (StreamOption) -> Unit,
) {
    val picker = StreamPicker { option ->
        streamPicker = null
        beginPreparing(
            id = release.id,
            newTitle = release.title,
            newSubtitle = release.subtitle,
            newPosterUrl = release.posterUrl,
        )
        scope.launch {
            play(option)
            if (mediaId == null && loadingError == null) cancelPreparing()
        }
    }
    streamPicker = picker
    scope.launch {
        picker.result = runCatching { search() }
            .getOrElse { StreamSearchResult(emptyList(), listOf("Search failed")) }
    }
}

@Composable
fun StreamPickerHost(gps: GlobalPlayerState, api: KobblerApi) {
    val picker = gps.streamPicker ?: return
    // Warming finishes after the search returns, so an option listed as unknown can become
    // verified-instant while the picker is open.
    LaunchedEffect(picker) {
        api.libraryEvents().collect { frame ->
            if (frame.kind != "stream_warm_ready") return@collect
            val hash = frame.data["info_hash"]?.jsonPrimitive?.contentOrNull ?: return@collect
            val providerId = frame.data["debrid_provider_id"]?.jsonPrimitive?.contentOrNull ?: return@collect
            val fid = frame.data["file_id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            picker.result = applyWarmReady(picker.result, hash, fid, providerId)
        }
    }
    val dismiss = { gps.streamPicker = null }
    // Drawn outside the scaffold, whose key handler is what carries a remote's Back to
    // InterceptBack, so the press is caught here.
    FocusScopeHost(id = "stream-picker") {
        Box(Modifier.backInput { dismiss(); true }) {
            StreamOptionsOverlay(
                options = picker.result?.options.orEmpty(),
                errors = picker.result?.errors.orEmpty(),
                loading = picker.result == null,
                onSelect = picker.onSelect,
                onDismiss = dismiss,
            )
        }
    }
}

/** A switch of the release already playing picks up at the playhead, not the last save. */
internal fun GlobalPlayerState.switchFromSec(player: dev.kobbler.kobplayer.Player?, id: String): Int? =
    player?.takeIf { mediaId == id }?.let { (timeline.toAbsoluteMs(it.currentMs.value) / 1000).toInt() }
