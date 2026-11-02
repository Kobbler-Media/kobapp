package dev.kobbler.app.ui.player

internal fun playbackTitleKey(mediaType: String?, mediaId: String?, showId: String?): String? = when (mediaType) {
    "movie" -> mediaId?.let { "movie:$it" }
    "episode" -> showId?.let { "show:$it" }
    else -> null
}
