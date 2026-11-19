package dev.kobbler.app.ui.navigation

internal fun linkDestination(link: String, autoPlay: Boolean = false): Destination? {
    fun after(prefix: String) = link.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
    after("/movies/")?.let { return Destination.MovieDetail(it, autoPlay = autoPlay) }
    (after("/tv/episodes/") ?: after("/sports/events/"))?.let {
        return Destination.TvShowDetail(it, autoPlay = autoPlay, isEpisodeId = true)
    }
    (after("/tv/") ?: after("/sports/"))?.let { return Destination.TvShowDetail(it, autoPlay = autoPlay) }
    after("/library/books/")?.let { return Destination.BookDetail(it) }
    return null
}
