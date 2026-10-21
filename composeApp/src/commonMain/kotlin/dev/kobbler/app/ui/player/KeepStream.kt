package dev.kobbler.app.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.kobbler.app.data.KeepProgress
import dev.kobbler.app.data.KeepState
import dev.kobbler.app.data.KobblerApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val KEEP_POLL_MS = 3_000L
private const val KEEP_POLL_FAILURES = 5

fun keepLabel(progress: KeepProgress?): String = when (progress?.state) {
    null -> "Keep in library"
    KeepState.Done -> "Saved"
    KeepState.Failed -> "Couldn't save to library"
    else -> progress.progress?.let { "Saving to library… ${(it * 100).toInt()}%" } ?: "Saving to library…"
}

private val KeepProgress?.canStart: Boolean get() = this == null || state == KeepState.Failed

// A press while an import is under way is the same request, not a second import.
fun GlobalPlayerState.keep(api: KobblerApi, sessionId: String, pollMs: Long = KEEP_POLL_MS) {
    if (!keeps[sessionId].canStart) return
    keeps[sessionId] = KeepProgress(KeepState.Queued)
    announceKeep(sessionId)
    teardownScope.launch {
        var progress = quietly { api.keepStream(sessionId) } ?: KeepProgress(KeepState.Failed)
        var failures = 0
        while (true) {
            keeps[sessionId] = progress
            announceKeep(sessionId)
            when (progress.state) {
                KeepState.Done -> return@launch api.invalidateKept()
                KeepState.Failed -> return@launch
                else -> Unit
            }
            delay(pollMs)
            val next = quietly { api.keepStatus(sessionId) }
            failures = if (next == null) failures + 1 else 0
            progress = next ?: if (failures >= KEEP_POLL_FAILURES) KeepProgress(KeepState.Failed) else progress
        }
    }
}

private suspend fun <T> quietly(call: suspend () -> T): T? = try {
    call()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

private fun GlobalPlayerState.announceKeep(sessionId: String) {
    if (this.sessionId != sessionId) return
    val progress = keeps[sessionId]
    when (progress?.state) {
        KeepState.Done, KeepState.Failed -> flashNote(keepLabel(progress))
        else -> holdNote(keepLabel(progress))
    }
}

@Immutable
class KeepAction(val label: String, val enabled: Boolean, val run: () -> Unit)

@Composable
fun rememberKeepAction(api: KobblerApi, gps: GlobalPlayerState, mediaType: String, candidates: Set<String>): KeepAction? {
    var last by remember(candidates) { mutableStateOf<String?>(null) }
    LaunchedEffect(candidates) {
        if (candidates.isEmpty() || !api.canStream) return@LaunchedEffect
        last = quietly { api.getRecentStreams() }
                ?.firstOrNull { it.mediaType == mediaType && it.mediaId in candidates }
                ?.id
    }
    val live = gps.sessionId.takeIf { gps.isDebrid && gps.mediaType == mediaType && gps.mediaId in candidates }
    val sessionId = live ?: last ?: return null
    val progress = gps.keeps[sessionId]
    return KeepAction(keepLabel(progress), progress.canStart) { gps.keep(api, sessionId) }
}
