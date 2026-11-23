package dev.kobbler.app.ui.player

import dev.kobbler.app.data.ApiException

/**
 * One or two plain sentences for a stream that would not play. The dialog keeps the full
 * diagnostic behind a details toggle; this is what the viewer reads first.
 */
internal fun streamFailureSummary(cause: Throwable?, tried: Int, lostMidway: Boolean = false, source: String? = null): String {
    val what = when {
        lostMidway -> "The stream stopped and couldn't be picked up again."
        tried == 0 -> "No streams were found for this."
        tried == 1 -> "That stream wouldn't play."
        else -> "None of the $tried streams we tried would play."
    }
    val why = (cause as? ApiException)?.let { e ->
        (if (source == "usenet") usenetReasonSentence(e) else null) ?: reasonSentence(e)
    }
    return if (why == null) what else "$what $why"
}

private fun usenetReasonSentence(e: ApiException): String? = when (e.reason) {
    "not_cached" -> "Parts of the release are gone from your usenet providers."
    "unavailable" -> "None of your usenet providers could serve it right now."
    "timeout" -> "Your usenet providers took too long to answer."
    else -> null
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

internal fun liveFailureSummary(channel: String, cause: Throwable?, serverReason: String? = null): String {
    val what = "Couldn't tune ${channel.ifBlank { "this channel" }}."
    val why = (cause as? ApiException)?.serverMessage?.takeIf { it.isNotBlank() }
        ?: serverReason?.takeIf { it.isNotBlank() }
        ?: "The tuner may not have a strong enough signal on it right now. Try again, or pick another channel."
    return "$what $why"
}
