package dev.kobbler.app.ui.player

import dev.kobbler.app.data.ApiException

/**
 * One or two plain sentences for a stream that would not play. The dialog keeps the full
 * diagnostic behind a details toggle; this is what the viewer reads first.
 */
internal fun streamFailureSummary(cause: Throwable?, tried: Int, lostMidway: Boolean = false): String {
    val what = when {
        lostMidway -> "The stream stopped and couldn't be picked up again."
        tried == 0 -> "No streams were found for this."
        tried == 1 -> "That stream wouldn't play."
        else -> "None of the $tried streams we tried would play."
    }
    val why = (cause as? ApiException)?.let(::reasonSentence)
    return if (why == null) what else "$what $why"
}

private fun reasonSentence(e: ApiException): String? = when (e.reason) {
    "blocked" -> "Your debrid service refused the release."
    "rate_limited" -> "Your debrid service is limiting requests right now — wait a minute and try again."
    "auth_failed" -> "Your debrid service rejected its API key. Check it in settings."
    "timeout" -> "Your debrid service took too long to answer."
    "not_video" -> "The release isn't a playable video."
    "not_cached" -> "The release isn't cached by your debrid service yet."
    "unavailable" -> "Your debrid service couldn't be reached."
    else -> e.serverMessage
}
