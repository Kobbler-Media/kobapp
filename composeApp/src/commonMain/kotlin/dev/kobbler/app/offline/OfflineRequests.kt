package dev.kobbler.app.offline

import dev.kobbler.app.data.Book
import dev.kobbler.app.data.Episode
import dev.kobbler.app.data.Movie
import dev.kobbler.app.data.TvShow

fun OfflineEngine.downloadMovie(movie: Movie) = enqueue(
    videoQuality(
        OfflineItem(
            kind = OfflineKind.Movie,
            mediaId = movie.id,
            title = movie.title,
            subtitle = movie.year.takeIf { it > 0 }?.toString(),
            posterUrl = movie.posterUrl,
            backdropUrl = movie.backdropUrl,
            durationSeconds = movie.runtimeMinutes?.times(60),
            files = listOf(OfflineFile(MediaFilePath)),
            kidsAllowed = movie.kidsAllowed,
        ),
    ),
)

/** Anything short of the original can only come from a copy the server makes. */
private fun videoQuality(item: OfflineItem): OfflineItem {
    val quality = DownloadQuality.current
    return item.copy(quality = quality.wire, exportQuality = quality.wire.takeIf { quality != DownloadQuality.Original })
}

fun OfflineEngine.downloadEpisodes(show: TvShow, episodes: List<Episode>) {
    for (season in show.seasons) {
        for (episode in season.episodes) {
            val id = episode.id ?: continue
            if (episode.filePath == null || episodes.none { it.id == id }) continue
            enqueue(
                videoQuality(OfflineItem(
                    kind = OfflineKind.Episode,
                    mediaId = id,
                    title = episode.title,
                    showId = show.id,
                    showTitle = show.title,
                    showPosterUrl = show.posterUrl,
                    seasonNumber = season.number,
                    episodeNumber = episode.number,
                    posterUrl = episode.thumbnailUrl ?: show.backdropUrl ?: show.posterUrl,
                    backdropUrl = show.backdropUrl,
                    durationSeconds = episode.runtimeMinutes?.times(60),
                    files = listOf(OfflineFile(MediaFilePath)),
                    kidsAllowed = show.kidsAllowed,
                )),
            )
        }
    }
}

/** Every episode there is, and every one that arrives after. */
fun OfflineEngine.downloadShow(show: TvShow) {
    downloadEpisodes(show, show.seasons.flatMap { it.episodes })
    setAutoDownload(show, true)
}

fun OfflineEngine.setAutoDownload(show: TvShow, on: Boolean) {
    if (!on) return unsubscribe(show.id)
    val existing = show.seasons.flatMap { it.episodes }.filter { it.filePath != null }.mapNotNull { it.id }.toSet()
    subscribe(ShowSubscription(show.id, show.title, show.posterUrl, knownEpisodeIds = existing))
}

/** The format the reader opens, or null when there is nothing on the book it can read. */
fun offlineBookFormat(book: Book): String? {
    val formats = book.formats.map { it.uppercase() }
    return ReadableOfflineFormats.firstOrNull { it in formats }
}

private val ReadableOfflineFormats = listOf("EPUB", "KEPUB", "CBZ")

fun OfflineEngine.downloadBook(book: Book) {
    val format = offlineBookFormat(book) ?: return
    enqueue(
        OfflineItem(
            kind = OfflineKind.Book,
            mediaId = book.id,
            title = book.title,
            subtitle = book.author,
            posterUrl = book.coverUrl,
            bookFormat = format,
            files = listOf(OfflineFile("book.${format.lowercase()}")),
        ),
    )
}

fun OfflineEngine.downloadAudiobook(book: Book) = enqueue(
    OfflineItem(
        kind = OfflineKind.Audiobook,
        mediaId = book.id,
        title = book.title,
        subtitle = book.narrator ?: book.author,
        posterUrl = book.coverUrl,
        durationSeconds = book.durationSeconds?.toInt(),
    ),
)
