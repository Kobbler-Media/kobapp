package dev.kobbler.app.offline

import dev.kobbler.app.data.KobblerApi
import kotlinx.serialization.Serializable

enum class OfflineKind { Movie, Episode, Book, Audiobook }

enum class OfflineState { Queued, Downloading, Waiting, Complete, Failed }

@Serializable
data class OfflineFile(
    val path: String,
    val size: Long? = null,
    val ranges: ByteRanges = ByteRanges(),
    val contentType: String? = null,
) {
    val complete: Boolean get() = size != null && ranges.covers(size)
}

@Serializable
data class OfflineItem(
    val kind: OfflineKind,
    val mediaId: String,
    val title: String,
    val subtitle: String? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val showId: String? = null,
    val showTitle: String? = null,
    val showPosterUrl: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val bookFormat: String? = null,
    val durationSeconds: Int? = null,
    val files: List<OfflineFile> = emptyList(),
    /** Whole files kept beside the media: subtitles, scrub thumbnails. */
    val extras: Set<String> = emptySet(),
    val session: KobblerApi.StreamSession? = null,
    val prepared: Boolean = false,
    val quality: String = DownloadQuality.Original.wire,
    /** Set when the file comes from a server-made copy rather than the title's own file. */
    val exportQuality: String? = null,
    val exportId: String? = null,
    val exportReady: Boolean = false,
    /** How far the server is with making that copy, while it is still making it. */
    val serverProgress: Float? = null,
    /** Which bytes the media file holds: an export id or the server's file id. Others are never mixed in. */
    val sourceId: String? = null,
    val kidsAllowed: Boolean = true,
    val state: OfflineState = OfflineState.Queued,
    val error: String? = null,
    val addedAtMs: Long = 0,
    val completedAtMs: Long? = null,
    val watched: Boolean = false,
    val keep: Boolean = false,
) {
    val key: String get() = keyOf(kind, mediaId)

    val isComic: Boolean get() = bookFormat?.lowercase() in ComicFormats

    val totalBytes: Long? get() = files.takeIf { f -> f.all { it.size != null } }?.sumOf { it.size!! }

    val filledBytes: Long get() = files.sumOf { it.ranges.filledBytes }

    // Files are sized one by one as the filler reaches them, so until all are known the share
    // of finished files is the only honest figure.
    val progress: Float
        get() = totalBytes?.takeIf { it > 0 }?.let { (filledBytes.toDouble() / it).toFloat().coerceIn(0f, 1f) }
            ?: if (files.isEmpty()) 0f else files.count { it.complete }.toFloat() / files.size

    fun file(path: String): OfflineFile? = files.firstOrNull { it.path == path }

    /** Whether playback can come through the proxy yet: a server copy only once it exists. */
    val playsLocally: Boolean get() = exportQuality == null || exportReady

    companion object {
        fun keyOf(kind: OfflineKind, mediaId: String) = "${kind.name.lowercase()}-$mediaId"
    }
}

val ComicFormats = setOf("cbz", "cbr", "cb7")

@Serializable
data class ShowSubscription(
    val showId: String,
    val title: String,
    val posterUrl: String? = null,
    val knownEpisodeIds: Set<String> = emptySet(),
)

@Serializable
data class PendingWrite(val method: String, val path: String, val body: String, val owner: String = "")

@Serializable
data class OfflineIndex(
    val items: Map<String, OfflineItem> = emptyMap(),
    val responses: Map<String, Set<String>> = emptyMap(),
    val images: Map<String, Set<String>> = emptyMap(),
    val subscriptions: Map<String, ShowSubscription> = emptyMap(),
    val pendingWrites: List<PendingWrite> = emptyList(),
)

fun OfflineIndex.completeKeys(): Set<String> =
    items.values.filter { it.state == OfflineState.Complete }.mapTo(HashSet()) { it.key }

fun OfflineIndex.completeShows(): Set<String> =
    items.values.filter { it.state == OfflineState.Complete }.mapNotNullTo(HashSet()) { it.showId }

const val MediaFilePath = "media"
const val ThumbnailsVttPath = "thumbnails.vtt"
const val ThumbnailsDir = "thumbnails"
