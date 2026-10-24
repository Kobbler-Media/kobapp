package dev.kobbler.app.data

import androidx.compose.runtime.Immutable
import kotlinx.datetime.LocalDate

enum class MediaType { Movie, TvShow, SportsEvent, Book, Audiobook }

@Immutable
data class ContinueItem(
    val id: String,
    val mediaType: MediaType,
    val title: String,
    val subtitle: String?,
    val imageUrl: String,
    val backdropUrl: String? = null,
    val progress: Float,
    val link: String? = null,
    val started: Boolean = false,
)

@Immutable
data class Movie(
    val id: String,
    val title: String,
    val year: Int,
    val posterUrl: String,
    val backdropUrl: String? = null,
    val logoUrl: String? = null,
    val rating: Float? = null,
    val genres: List<String> = emptyList(),
    val synopsis: String? = null,
    val tagline: String? = null,
    val contentRating: String? = null,
    val kidsOverride: Boolean? = null,
    val kidsAllowed: Boolean = false,
    val duration: String? = null,
    val runtimeMinutes: Int? = null,
    val cast: List<CastMember> = emptyList(),
    val directors: List<String> = emptyList(),
    val writers: List<String> = emptyList(),
    val filePath: String? = null,
    val wantStatus: String? = null,
    val isWatched: Boolean = false,
    val watchPositionSeconds: Int? = null,
    val inWatchlist: Boolean = false,
    val releaseDate: LocalDate? = null,
    val releaseDates: ReleaseDates? = null,
) {
    val isWanted: Boolean get() = isWantedStatus(wantStatus)

    fun canBeWanted(canWant: Boolean): Boolean = canBeWanted(filePath, wantStatus, canWant)
}

@Immutable
data class ReleaseDates(
    val region: String? = null,
    val premiere: LocalDate? = null,
    val theatricalLimited: LocalDate? = null,
    val theatrical: LocalDate? = null,
    val digital: LocalDate? = null,
    val physical: LocalDate? = null,
    val tv: LocalDate? = null,
) {
    val cinema: LocalDate? get() = theatrical ?: theatricalLimited ?: premiere
}

fun isWantedStatus(wantStatus: String?): Boolean =
    !wantStatus.isNullOrEmpty() && wantStatus != "none"

/** Streaming a debrid copy and asking for a download are separate things, so the thing
 *  that can only stream is exactly the one worth offering to want. */
fun canBeWanted(filePath: String?, wantStatus: String?, canWant: Boolean): Boolean =
    filePath == null && canWant && !isWantedStatus(wantStatus)

@Immutable
data class ContinueEpisode(
    val seasonIdx: Int,
    val episodeIdx: Int,
    val episodeId: String,
    val positionSeconds: Int = 0,
)

@Immutable
data class TvShow(
    val id: String,
    val title: String,
    val year: Int,
    val posterUrl: String,
    val backdropUrl: String? = null,
    val logoUrl: String? = null,
    val network: String? = null,
    val rating: Float? = null,
    val contentRating: String? = null,
    val kidsOverride: Boolean? = null,
    val kidsAllowed: Boolean = false,
    val synopsis: String? = null,
    val cast: List<CastMember> = emptyList(),
    val seasons: List<Season> = emptyList(),
    val continueEpisode: ContinueEpisode? = null,
    val isMonitored: Boolean = false,
    /** Server's `none` / `new_only` / `all` / `fill_gaps`. */
    val autoWantMode: String = "none",
    val inWatchlist: Boolean = false,
    val nextAiring: String? = null,
    val isSport: Boolean = false,
)

@Immutable
data class Season(
    val number: Int,
    val episodes: List<Episode>,
    val id: String? = null,
)

fun List<Season>.indexOfEpisode(episodeId: String): Pair<Int, Int>? {
    forEachIndexed { sIdx, season ->
        val eIdx = season.episodes.indexOfFirst { it.id == episodeId }
        if (eIdx >= 0) return sIdx to eIdx
    }
    return null
}

@Immutable
data class Episode(
    val id: String? = null,
    val number: Int,
    val title: String,
    val synopsis: String? = null,
    val thumbnailUrl: String? = null,
    val duration: String? = null,
    val runtimeMinutes: Int? = null,
    val filePath: String? = null,
    /** Watch progress 0.0–1.0, or null if not watched. */
    val watchProgress: Float? = null,
    val isWatched: Boolean = false,
    val wantStatus: String? = null,
    val airDate: LocalDate? = null,
) {
    val isWanted: Boolean get() = isWantedStatus(wantStatus)

    fun canBeWanted(canWant: Boolean): Boolean = canBeWanted(filePath, wantStatus, canWant)
}

@Immutable
data class CastMember(
    val name: String,
    val character: String? = null,
    val imageUrl: String? = null,
    val personId: String? = null,
)

@Immutable
data class BookAuthor(val id: String, val name: String)

@Immutable
data class Book(
    val id: String,
    val title: String,
    val author: String,
    val coverUrl: String,
    val authors: List<BookAuthor> = emptyList(),
    val seriesId: String? = null,
    val seriesName: String? = null,
    val seriesIndex: Double? = null,
    val rating: Float? = null,
    val description: String? = null,
    val hasAudiobook: Boolean = false,
    val formats: List<String> = emptyList(),
    val narrator: String? = null,
    val durationSeconds: Double? = null,
    val genres: List<String> = emptyList(),
    val isInLibrary: Boolean = true,
    val wantStatus: String? = null,
    val seriesMemberships: List<BookSeriesEntry> = emptyList(),
)

@Immutable
data class BookSeriesEntry(
    val seriesId: String,
    val seriesName: String,
    val seriesIndex: Double? = null,
)

@Immutable
data class Author(
    val id: String,
    val name: String,
    val bookCount: Long = 0,
    val seriesCount: Long = 0,
    val coverUrls: List<String> = emptyList(),
    val imageUrl: String? = null,
    val bio: String? = null,
    val topSeriesNames: String? = null,
)

@Immutable
data class Series(
    val id: String,
    val name: String,
    val bookCount: Long = 0,
    val coverUrls: List<String> = emptyList(),
    val imageUrl: String? = null,
    val description: String? = null,
    val authorNames: String = "",
)

@Immutable
data class SearchResult(
    val type: String,
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val imageUrl: String? = null,
    val link: String? = null,
)

// -- Wanted --

@Immutable
data class WantedItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val coverUrl: String,
    val wantStatus: String,
    val attemptStatus: String?,
    val mediaType: String,
)

// -- Person --

@Immutable
data class Person(
    val id: String,
    val name: String,
    val imageUrl: String?,
    val bio: String?,
    val birthDate: String?,
    val deathDate: String?,
    val knownForDepartment: String?,
    val placeOfBirth: String?,
)

@Immutable
data class FilmographyCredit(
    val providerId: String,
    val mediaType: String,
    val title: String,
    val year: Int?,
    val posterUrl: String?,
    val character: String?,
    val department: String?,
    val job: String?,
    val creditType: String,
    val inLibrary: Boolean,
    val libraryId: String?,
    val wantStatus: String?,
    val dismissed: Boolean,
)

// -- IPTV --

@Immutable
data class IptvChannel(
    val id: String,
    val name: String,
    val iconUrl: String?,
    val category: String?,
    val language: String?,
    val country: String?,
    val sourceCount: Long = 0,
    val isFavourite: Boolean = false,
    val favouriteId: String? = null,
    val nowPlaying: IptvEpgEntry? = null,
    val nextUp: IptvEpgEntry? = null,
)

@Immutable
data class IptvEpgEntry(
    val id: String,
    val title: String?,
    val description: String?,
    val startTime: String,
    val stopTime: String,
    val categories: List<String> = emptyList(),
)

@Immutable
data class IptvGroup(
    val id: String,
    val name: String,
    val slug: String,
    val iconUrl: String?,
    val channelCount: Long,
)

@Immutable
data class IptvWatchMatch(
    val watchId: String,
    val channelId: String,
    val channelName: String,
    val channelIconUrl: String?,
    val epgTitle: String,
    val startTime: String,
    val stopTime: String,
    val isLive: Boolean,
)

// -- Live TV (tuner) --

@Immutable
data class LiveTuner(
    val id: String,
    val friendlyName: String,
    val modelNumber: String?,
    val tunerCount: Int,
    val channelCount: Int,
    val enabledChannelCount: Int,
    val leasesInUse: Int,
    val lastScanAt: String?,
    val lastGuideSyncAt: String?,
)

@Immutable
data class LiveChannel(
    val id: String,
    val name: String,
    val lcn: String?,
    val callsign: String?,
    val logoUrl: String?,
    val network: String?,
    val region: String?,
    val kind: String?,
    val hd: Boolean,
    val enabled: Boolean,
    val favourite: Boolean,
    val muxLabel: String?,
    val typeLabel: String?,
    val hasGuide: Boolean,
    val guideEntryCount: Int,
    val videoCodec: String?,
    val audioCodec: String?,
    val signalQuality: Int?,
    val now: LiveGuideEntry? = null,
    val next: LiveGuideEntry? = null,
    val minutesLeft: Int? = null,
)

@Immutable
data class LiveFacet(val value: String, val label: String, val count: Int)

@Immutable
data class LiveFacets(
    val network: List<LiveFacet> = emptyList(),
    val region: List<LiveFacet> = emptyList(),
    val type: List<LiveFacet> = emptyList(),
    val guide: List<LiveFacet> = emptyList(),
    val status: List<LiveFacet> = emptyList(),
    val mux: List<LiveFacet> = emptyList(),
)

@Immutable
data class LiveGuideEntry(
    val id: String,
    val channelId: String,
    val startTime: String,
    val endTime: String,
    val title: String,
    val episodeNumber: String?,
    val episodeTitle: String?,
    val synopsis: String?,
    val genres: List<String>,
    val imageUrl: String?,
    val posterUrl: String?,
    val isFirst: Boolean,
    val episodeKey: String?,
    val durationSeconds: Long,
    val progressPct: Float?,
    val recordingId: String?,
    val recordingStatus: String?,
    val repeatCount: Int,
)

@Immutable
data class LiveGuideChannel(val channel: LiveChannel, val entries: List<LiveGuideEntry>)

@Immutable
data class LiveOnNowItem(
    val channel: LiveChannel,
    val guide: LiveGuideEntry,
    val minutesLeft: Int?,
    val startsInMinutes: Int? = null,
    val reason: String,
    /** Null when the server has too little history — the UI hides the dots rather than inventing a level. */
    val confidence: Float?,
)

@Immutable
data class LiveOnNowGroup(
    val key: String,
    val title: String,
    val subtitle: String,
    val items: List<LiveOnNowItem>,
)

@Immutable
data class LiveClashNotice(
    val guide: LiveGuideEntry,
    val reason: String,
    val alternative: LiveGuideEntry?,
)

@Immutable
data class LiveOnNow(
    val generatedAt: String,
    val tunerNotice: LiveClashNotice?,
    val groups: List<LiveOnNowGroup>,
)

@Immutable
data class LiveLease(
    val id: String,
    val channelId: String?,
    val channelName: String?,
    val tunerIndex: Int,
    val holderKind: String,
    val recordingId: String?,
    val acquiredAt: String?,
    val expiresAt: String?,
)

@Immutable
data class LiveLeases(val total: Int, val inUse: Int, val items: List<LiveLease>)

@Immutable
data class LiveAdBreak(
    val id: String,
    val startSeconds: Double,
    val endSeconds: Double,
    val method: String?,
    val confidence: Float?,
    val adsKnown: Int,
    val adsTotal: Int,
    val correctedAt: String?,
)

@Immutable
data class LiveRecording(
    val id: String,
    val channelId: String?,
    val channelName: String?,
    val guideId: String?,
    val seriesRuleId: String?,
    val title: String,
    val episodeNumber: String?,
    val episodeTitle: String?,
    val synopsis: String?,
    val scheduledStart: String?,
    val scheduledEnd: String?,
    val actualStart: String?,
    val actualEnd: String?,
    val status: String,
    val tunerIndex: Int?,
    val durationSeconds: Long,
    val videoCodec: String?,
    val audioCodec: String?,
    val sizeBytes: Long,
    val movedToGuideId: String?,
    val imageUrl: String?,
    val episodeId: String?,
    val movieId: String?,
    val error: String?,
    val isInProgress: Boolean,
    val secondsOnDisk: Long,
    val playableFromStart: Boolean,
    val adBreaks: List<LiveAdBreak>,
    val provenance: String?,
    val adPosture: String,
    val adSkipDisabled: Boolean,
)

@Immutable
data class LiveSeriesRule(
    val id: String,
    val seriesId: String?,
    val title: String,
    val enabled: Boolean,
    val channelIds: List<String>,
    val weekdays: List<Int>,
    val windowStart: String?,
    val windowEnd: String?,
    val newOnly: Boolean,
    val minSeason: Int?,
    val keepCount: Int?,
    val keepUntil: String?,
    val paddingBeforeSecs: Int,
    val paddingAfterSecs: Int,
    val onClash: String,
    val priority: Int,
    val queuedCount: Int,
)

@Immutable
data class LiveClusterMember(
    val guideId: String,
    val channelId: String,
    val channelName: String,
    val lcn: String?,
    val hd: Boolean,
    val startTime: String,
    val record: Boolean,
    val reason: String,
)

@Immutable
data class LiveCluster(val label: String, val startTime: String, val members: List<LiveClusterMember>)

@Immutable
data class LiveBuffer(
    val startTime: String?,
    val endTime: String?,
    val liveEdgeSeconds: Double,
    val durationSeconds: Double,
    val retainedSeconds: Double,
    val capSeconds: Double,
    val tuneError: String? = null,
)

@Immutable
data class LiveSession(
    val sessionId: String,
    val channelId: String,
    val method: String,
    val directUrl: String?,
    val playlistUrl: String?,
    val videoCodec: String?,
    val audioCodec: String?,
    val hd: Boolean,
    val interlaced: Boolean?,
    val thumbnailsUrl: String?,
    val width: Int?,
    val height: Int?,
    val bufferStartTime: String?,
    val tunersInUse: Int,
    val tunerCount: Int,
    val now: LiveGuideEntry?,
    val next: LiveGuideEntry?,
) {
    /** kobapp is the DirectPlay path: raw TS first, playlist only as a fallback. */
    val streamUrl: String? get() = directUrl ?: playlistUrl
}

@Immutable
data class LiveRulePreview(
    val clusters: List<LiveCluster>,
    val wouldRecord: Int,
    val suppressed: Int,
)

// -- Discover --

enum class LibraryStatus { InLibrary, Wanted, NotInLibrary }

@Immutable
data class DiscoverItem(
    val provider: String,
    val providerId: String,
    val mediaType: String,
    val title: String,
    val year: Int?,
    val description: String?,
    val posterUrl: String,
    val backdropUrl: String?,
    val genres: List<String>,
    val rating: Float?,
    val ratingCount: Int?,
    val authors: List<String>,
    val libraryId: String?,
    val wantStatus: String?,
    val isInLibrary: Boolean,
) {
    val libraryStatus: LibraryStatus get() = when {
        isInLibrary -> LibraryStatus.InLibrary
        isWantedStatus(wantStatus) -> LibraryStatus.Wanted
        else -> LibraryStatus.NotInLibrary
    }
}

@Immutable
data class DiscoverSearchResults(
    val movies: List<DiscoverItem>,
    val tvShows: List<DiscoverItem>,
    val books: List<DiscoverItem>,
) {
    val totalCount get() = movies.size + tvShows.size + books.size
}

@Immutable
data class HomeData(
    val watchNext: List<ContinueItem> = emptyList(),
    val recentlyAdded: List<RecentlyAddedItem> = emptyList(),
    val stremioContinue: List<StremioContinueItem> = emptyList(),
    val forYou: List<ForYouItem> = emptyList(),
    val sections: List<DiscoverSection> = emptyList(),
)

@Immutable
data class ForYouItem(
    val item: DiscoverItem,
    val because: List<String>,
    val seeds: List<ForYouSeed> = emptyList(),
)

@Immutable
data class ForYouSeed(val mediaType: String, val tmdbId: Long, val title: String)

@Immutable
data class StremioContinueItem(
    val item: DiscoverItem,
    val subtitle: String?,
    val season: Int?,
    val progress: Float?,
)

@Immutable
data class DiscoverSection(
    val title: String,
    val sectionKey: String,
    val sortOrder: Int,
    val items: List<DiscoverItem>,
)

@Immutable
data class RecentlyAddedItem(
    val itemType: String,
    val id: String,
    val title: String,
    val subtitle: String,
    val imageUrl: String,
    val backdropUrl: String? = null,
    val link: String? = null,
)

@Immutable
data class DiscoverDetail(
    val mediaType: String,
    val provider: String,
    val title: String,
    val year: Int?,
    val description: String?,
    val posterUrl: String,
    val backdropUrl: String?,
    val genres: List<String>,
    val rating: Float?,
    val cast: List<CastMember>,
    val network: String?,
    val runtime: Int?,
    val authors: List<String>,
    val libraryId: String?,
    val wantStatus: String?,
    val isInLibrary: Boolean,
    val identifiers: Map<String, String>,
    val providerId: String,
    val seasons: List<DiscoverSeason> = emptyList(),
    val ratingCount: Int? = null,
    val releaseDate: LocalDate? = null,
    val releaseDates: ReleaseDates? = null,
) {
    val libraryStatus: LibraryStatus get() = when {
        isInLibrary -> LibraryStatus.InLibrary
        isWantedStatus(wantStatus) -> LibraryStatus.Wanted
        else -> LibraryStatus.NotInLibrary
    }
}

@Immutable
data class DiscoverSeason(
    val seasonNumber: Int,
    val title: String?,
    val episodeCount: Int?,
    val airDate: String?,
)

@Immutable
data class DiscoverEpisode(
    val episodeNumber: Int,
    val title: String?,
    val airDate: String?,
    val runtime: Int?,
    val stillUrl: String?,
    val description: String? = null,
)

// -- Debrid Streaming --

@Immutable
data class StreamOption(
    val id: String,
    val releaseTitle: String,
    val size: Long,
    val indexer: String,
    val infoHash: String?,
    val quality: StreamQuality,
    val qualityJson: kotlinx.serialization.json.JsonObject?,
    val totalScore: Double,
    val debridProvider: String,
    val debridProviderId: String,
    val cached: Boolean,
    val sourceType: String,
    val fileId: Int?,
    val publishDate: String?,
    val fileName: String? = null,
    val playback: String? = null,
    val availability: String? = null,
    val audioLanguages: List<String> = emptyList(),
)

@Immutable
data class StreamQuality(
    val resolution: String?,
    val codec: String?,
    val hdr: String?,
    val audio: String?,
)

@Immutable
data class DiscoverEpisodeRef(val season: Int, val episode: Int)

@Immutable
data class StreamSearchResult(
    val options: List<StreamOption>,
    val errors: List<String>,
)

/** 404 is "not generated yet"; 410 is the session itself being gone, so asking again is pointless. */
sealed interface ThumbnailsProbe {
    data class Ready(val url: String) : ThumbnailsProbe
    data object Pending : ThumbnailsProbe
    data object Gone : ThumbnailsProbe
}

sealed interface StreamResolveResult {
    data class Ready(val session: KobblerApi.StreamSession) : StreamResolveResult
    data class Caching(val debridProviderId: String, val torrentId: String, val option: StreamOption) : StreamResolveResult
}

@Immutable
data class StreamCachingStatus(
    val status: String,
    val progress: Double?,
    val message: String?,
)

@Immutable
data class WatchHistoryEntry(
    val mediaType: String,
    val tmdbId: Long?,
    val title: String,
    val year: Int?,
    val posterUrl: String?,
    val lastWatchedAt: String?,
    val sources: List<String>,
    val excluded: Boolean,
)

data class WatchHistoryPage(val items: List<WatchHistoryEntry>, val total: Int)
