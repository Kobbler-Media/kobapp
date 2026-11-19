package dev.kobbler.app.ui.player

internal const val CastArtTag = "cast-art"

internal data class CastArt(val url: String, val aspect: Float) {
    val tall: Boolean get() = aspect < 1f
}

// An episode often has only its show's poster; drawn into a 16:9 frame it loses most of itself.
internal fun castArt(posterUrl: String?, backdropUrl: String?): CastArt? =
    backdropUrl?.let { CastArt(it, 16f / 9f) } ?: posterUrl?.let { CastArt(it, 2f / 3f) }
