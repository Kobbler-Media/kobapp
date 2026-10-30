package dev.kobbler.app.offline

import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.ui.player.audiobook.pickBestAudioSource
import dev.kobbler.app.ui.player.thumbnailVttBaseUrl
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * Everything a downloaded title's pages and player read besides the media itself: the
 * responses they are drawn from, the art, the subtitles and the scrub thumbnails.
 */
internal suspend fun prepare(engine: OfflineEngine, key: String) {
    val item = engine.store.item(key) ?: return
    val api = engine.api.value ?: throw UpstreamUnavailable("Not signed in")
    val kept = Kept(engine, api, key)
    when (item.kind) {
        OfflineKind.Movie -> {
            val movie = withContext(OfflineCapture(key)) {
                api.getMovieProgress(item.mediaId)
                api.getMovie(item.mediaId)
            }
            kept.images(listOfNotNull(movie.posterUrl, movie.backdropUrl, movie.logoUrl) + movie.cast.mapNotNull { it.imageUrl })
            engine.store.updateItem(key) {
                it.copy(
                    title = movie.title,
                    subtitle = movie.year.takeIf { y -> y > 0 }?.toString(),
                    posterUrl = movie.posterUrl,
                    backdropUrl = movie.backdropUrl,
                    durationSeconds = movie.runtimeMinutes?.times(60),
                    kidsAllowed = movie.kidsAllowed,
                )
            }
            kept.video(item)
        }
        OfflineKind.Episode -> {
            val show = withContext(OfflineCapture(key)) {
                api.getEpisodeProgress(item.mediaId)
                val showId = item.showId ?: api.getShowIdForEpisode(item.mediaId)
                api.getShow(showId)
            }
            val season = show.seasons.firstOrNull { s -> s.episodes.any { it.id == item.mediaId } }
            val episode = season?.episodes?.firstOrNull { it.id == item.mediaId }
            engine.store.updateItem(key) {
                it.copy(
                    title = episode?.title ?: it.title,
                    showId = show.id,
                    showTitle = show.title,
                    showPosterUrl = show.posterUrl,
                    seasonNumber = season?.number,
                    episodeNumber = episode?.number,
                    posterUrl = episode?.thumbnailUrl ?: show.backdropUrl ?: show.posterUrl,
                    backdropUrl = show.backdropUrl,
                    durationSeconds = episode?.runtimeMinutes?.times(60),
                    kidsAllowed = show.kidsAllowed,
                )
            }
            kept.images(
                listOfNotNull(show.posterUrl, show.backdropUrl, show.logoUrl) +
                    show.cast.mapNotNull { it.imageUrl } +
                    season?.episodes.orEmpty().mapNotNull { it.thumbnailUrl },
            )
            kept.video(item)
        }
        OfflineKind.Book -> {
            val book = withContext(OfflineCapture(key)) {
                api.readerProgressOrNull(item.mediaId)
                api.getBook(item.mediaId)
            }
            kept.images(listOf(book.coverUrl))
            engine.store.updateItem(key) { it.copy(title = book.title, subtitle = book.author, posterUrl = book.coverUrl) }
        }
        OfflineKind.Audiobook -> {
            val (book, files) = withContext(OfflineCapture(key)) {
                api.getAudioPosition(item.mediaId)
                runCatching { api.getUnifiedChapters(item.mediaId) }
                runCatching { api.getTranscriptSegments(item.mediaId) }
                runCatching { api.getReadAlongData(item.mediaId) }
                api.getBook(item.mediaId) to pickBestAudioSource(api.getAudioFiles(item.mediaId))
            }
            kept.images(listOf(book.coverUrl))
            engine.store.updateItem(key) { current ->
                current.copy(
                    title = book.title,
                    subtitle = book.narrator ?: book.author,
                    posterUrl = book.coverUrl,
                    durationSeconds = book.durationSeconds?.toInt(),
                    files = files.map { f -> current.file(OfflineEngine.AudioDir + f.id) ?: OfflineFile(OfflineEngine.AudioDir + f.id) },
                )
            }
        }
    }
    engine.store.updateItem(key) { it.copy(prepared = true) }
}

private class Kept(private val engine: OfflineEngine, private val api: KobblerApi, private val key: String) {
    suspend fun images(urls: List<String>) {
        for (url in urls.filter { it.isNotBlank() }.distinct()) {
            if (engine.store.hasImage(url)) engine.store.addImageOwner(url, key)
            else fetch(url)?.let { engine.store.putImage(url, key, it) }
        }
    }

    suspend fun video(item: OfflineItem) {
        val session = engine.openDownloadSession(item)
        val extras = mutableSetOf<String>()
        for (sub in session.subtitles) {
            val url = sub.url ?: continue
            val path = subtitlePath(sub) ?: continue
            fetch(url)?.let { engine.store.writeWhole(key, path, it); extras += path }
        }
        session.thumbnailsUrl?.let { vttUrl ->
            val vtt = fetch(vttUrl) ?: return@let
            val base = thumbnailVttBaseUrl(vttUrl)
            val sprites = vtt.decodeToString().lines()
                .map { it.trim().substringBefore('#') }
                .filter { it.isNotEmpty() && !it.contains("-->") && !it.startsWith("WEBVTT") && it.contains('.') }
                .distinct()
            for (sprite in sprites) {
                val bytes = fetch(if (sprite.contains("://")) sprite else base + sprite) ?: return@let
                engine.store.writeWhole(key, "$ThumbnailsDir/${sprite.substringAfterLast('/')}", bytes)
            }
            engine.store.writeWhole(key, ThumbnailsVttPath, vtt)
            extras += ThumbnailsVttPath
        }
        // The title's own file goes down as is only when the server would send it so; anything
        // else is a copy the server makes, and holding a transcode open for it would be waste.
        // A direct session stays: the server may answer that the original is the copy to take.
        val exportQuality = item.exportQuality ?: DownloadQuality.Original.wire.takeIf { session.method != OfflineEngine.DirectPlay }
        if (session.method != OfflineEngine.DirectPlay) engine.releaseDownloadSession(key)
        engine.store.updateItem(key) { it.copy(session = session, extras = it.extras + extras, exportQuality = exportQuality) }
    }

    private suspend fun fetch(url: String): ByteArray? = try {
        val response = api.fileClient.get(url) { api.authHeaders.forEach { (k, v) -> header(k, v) } }
        if (response.status.isSuccess()) response.readRawBytes() else null
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
