package dev.kobbler.app.data

import dev.kobbler.app.platform.browserCapabilities
import dev.kobbler.app.platform.connectRoundTripMs
import dev.kobbler.kobplayer.deviceDecodesInterlaced
import dev.kobbler.kobplayer.deviceHdrFormats
import dev.kobbler.app.platform.AppVersion
import dev.kobbler.app.platform.clientKind
import dev.kobbler.app.platform.deviceLabel
import dev.kobbler.app.ui.components.DataCache
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import dev.kobbler.app.ui.reader.pack.Manifest
import dev.kobbler.app.ui.reader.pack.PackChapter
import dev.kobbler.app.ui.reader.pack.PackJson
import dev.kobbler.app.ui.reader.pack.Position
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import dev.kobbler.app.offline.OfflineException
import dev.kobbler.app.offline.OfflineHooks
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import dev.kobbler.app.ui.dashboard.KEY_STREAMING_QUALITY
import io.ktor.client.HttpClient

private val json = Json { ignoreUnknownKeys = true }

/**
 * Budget for routes the server fulfils by building something — a Calibre format
 * conversion, a TTS render. The client's default request timeout is sized for JSON and
 * would abort these long before the server is done.
 */
const val BookFetchTimeoutMs = 5 * 60 * 1000L

/** Read size for the TTS event stream. One `audio` event is several hundred KB of base64. */
private const val TtsStreamChunkBytes = 64 * 1024

/** Routes the server serves unauthenticated, keyed on a session id. See [playerUrlNeedsToken]. */
private val PublicPlaybackPaths = listOf(
    "/api/v1/admin/playback/sessions/",
    "/api/v1/admin/livetv/sessions/",
)

/**
 * Live TS carries its session in the query rather than the path, and only that form
 * is public — without it the server would open a second physical tuner, so it still
 * needs a credential.
 */
private const val LiveDirectPath = "/api/v1/admin/livetv/channels/"

/**
 * Does a player need a credential to fetch this?
 *
 * Everything the server keys on a session id is served without one: the client had to be
 * authorised to mint the session, and the id is the capability. What is left is the debrid
 * play route and the audiobook files, whose ids are permanent — those keep their auth.
 */
internal fun playerUrlNeedsToken(url: String): Boolean = when {
    PublicPlaybackPaths.any { it in url } -> false
    LiveDirectPath in url && "session=" in url -> false
    else -> true
}

/** Carry the credential in the query, which is the only place libVLC will take one. */
internal fun withPlaybackToken(url: String, token: String): String {
    val separator = if ('?' in url) '&' else '?'
    return "$url$separator" + "token=${token.encodeURLParameter()}"
}

/**
 * How long a minted playback token is asked to live, and how long it is used for.
 *
 * A token cannot be renegotiated once a player is reading from the socket, so it is
 * replaced well inside its life rather than at the end of it.
 */
private const val PlaybackTokenTtlHours = 24
private val PlaybackTokenReuse = 20.hours

class ApiException(
    val statusCode: Int,
    val statusDescription: String,
    val serverMessage: String? = null,
    val reason: String? = null,
) : Exception(serverMessage ?: "Server error ($statusCode $statusDescription)")

private class ServerError(val message: String?, val reason: String?)

private fun parseServerError(body: String): ServerError = try {
    val obj = Json.parseToJsonElement(body).jsonObject
    ServerError(
        message = (obj["error"] ?: obj["message"])?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
        reason = obj["reason"]?.jsonPrimitive?.contentOrNull,
    )
} catch (_: Exception) {
    ServerError(null, null)
}

// -- API response DTOs --

@Serializable
data class DashboardResponse(
    @SerialName("read_next") val readNext: List<InProgressItem> = emptyList(),
    @SerialName("recent_books") val recentBooks: List<RecentBook> = emptyList(),
    @SerialName("recent_ebooks") val recentEbooks: List<RecentBook> = emptyList(),
    @SerialName("recent_audiobooks") val recentAudiobooks: List<RecentBook> = emptyList(),
    @SerialName("recent_movies") val recentMovies: List<RecentMovie> = emptyList(),
    @SerialName("recent_tv") val recentTv: List<RecentTvEntry> = emptyList(),
    @SerialName("recent_sports") val recentSports: List<RecentTvEntry> = emptyList(),
)

@Serializable
data class InProgressItem(
    @SerialName("media_type") val mediaType: String,
    @SerialName("media_id") val mediaId: String,
    val title: String,
    val subtitle: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    val link: String,
    @SerialName("progress_percent") val progressPercent: Double? = null,
    @SerialName("position_seconds") val positionSeconds: Int? = null,
    @SerialName("duration_seconds") val durationSeconds: Double? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("backdrop_url") val backdropUrl: String? = null,
)

@Serializable
data class RecentItem(
    @SerialName("item_type") val itemType: String,
    val id: String,
    val title: String,
    val subtitle: String,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("backdrop_url") val backdropUrl: String? = null,
    val link: String,
    @SerialName("added_at") val addedAt: String? = null,
)

@Serializable
data class RecentBook(
    val id: String,
    val title: String,
    val authors: String,
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("added_at") val addedAt: String? = null,
)

@Serializable
data class RecentMovie(
    val id: String,
    val title: String,
    val year: Int? = null,
    @SerialName("poster_url") val posterUrl: String? = null,
    @SerialName("backdrop_url") val backdropUrl: String? = null,
    @SerialName("added_at") val addedAt: String? = null,
)

@Serializable
data class RecentTvEntry(
    @SerialName("show_id") val showId: String,
    @SerialName("show_title") val showTitle: String,
    @SerialName("show_poster_url") val showPosterUrl: String? = null,
    @SerialName("show_backdrop_url") val showBackdropUrl: String? = null,
    @SerialName("season_identifier") val seasonIdentifier: String,
    @SerialName("episode_id") val episodeId: String,
    @SerialName("episode_identifier") val episodeIdentifier: String,
    @SerialName("episode_title") val episodeTitle: String? = null,
    @SerialName("added_at") val addedAt: String? = null,
)

// -- Movie detail DTO --

@Serializable
data class MovieDetailDto(
    val id: String,
    val title: String,
    @SerialName("sort_title") val sortTitle: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    val year: Int? = null,
    val runtime: Int? = null,
    val description: String? = null,
    val tagline: String? = null,
    @SerialName("poster_url") val posterUrl: String? = null,
    @SerialName("backdrop_url") val backdropUrl: String? = null,
    @SerialName("logo_url") val logoUrl: String? = null,
    val genres: List<String>? = null,
    val rating: Double? = null,
    @SerialName("rating_count") val ratingCount: Int? = null,
    @SerialName("content_rating") val contentRating: String? = null,
    @SerialName("kids_override") val kidsOverride: Boolean? = null,
    @SerialName("kids_allowed") val kidsAllowed: Boolean = false,
    @SerialName("is_in_library") val isInLibrary: Boolean = false,
    @SerialName("want_status") val wantStatus: String = "",
    val watched: Boolean? = null,
    @SerialName("watch_position_seconds") val watchPositionSeconds: Int? = null,
    @SerialName("in_watchlist") val inWatchlist: Boolean = false,
    val cast: List<CastDto> = emptyList(),
    val crew: List<CrewDto> = emptyList(),
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("release_dates") val releaseDates: ReleaseDatesDto? = null,
)

@Serializable
data class ReleaseDatesDto(
    val region: String? = null,
    val premiere: String? = null,
    @SerialName("theatrical_limited") val theatricalLimited: String? = null,
    val theatrical: String? = null,
    val digital: String? = null,
    val physical: String? = null,
    val tv: String? = null,
)

internal fun ReleaseDatesDto.toModel() = ReleaseDates(
    region = region,
    premiere = parseIsoDate(premiere),
    theatricalLimited = parseIsoDate(theatricalLimited),
    theatrical = parseIsoDate(theatrical),
    digital = parseIsoDate(digital),
    physical = parseIsoDate(physical),
    tv = parseIsoDate(tv),
)

@Serializable
data class CastDto(
    val id: String? = null,
    @SerialName("person_id") val personId: String? = null,
    @SerialName("person_name") val personName: String,
    @SerialName("person_image_url") val personImageUrl: String? = null,
    @SerialName("character_name") val characterName: String? = null,
    @SerialName("cast_order") val castOrder: Int? = null,
)

@Serializable
data class CrewDto(
    @SerialName("person_id") val personId: String? = null,
    @SerialName("person_name") val personName: String,
    val department: String,
    val job: String,
)

// -- Show detail DTO --

@Serializable
data class ContinueEpisodeDto(
    @SerialName("season_idx") val seasonIdx: Int,
    @SerialName("episode_idx") val episodeIdx: Int,
    @SerialName("episode_id") val episodeId: String,
    @SerialName("position_seconds") val positionSeconds: Int = 0,
)

@Serializable
data class ShowDetailDto(
    val id: String,
    val title: String,
    @SerialName("sort_title") val sortTitle: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    val year: Int? = null,
    val status: String? = null,
    val description: String? = null,
    @SerialName("poster_url") val posterUrl: String? = null,
    @SerialName("backdrop_url") val backdropUrl: String? = null,
    @SerialName("logo_url") val logoUrl: String? = null,
    val genres: List<String>? = null,
    val rating: Double? = null,
    @SerialName("rating_count") val ratingCount: Int? = null,
    @SerialName("content_rating") val contentRating: String? = null,
    @SerialName("kids_override") val kidsOverride: Boolean? = null,
    @SerialName("kids_allowed") val kidsAllowed: Boolean = false,
    val network: String? = null,
    @SerialName("content_category") val contentCategory: String? = null,
    val runtime: Int? = null,
    val seasons: List<SeasonDto> = emptyList(),
    @SerialName("is_monitored") val isMonitored: Boolean = false,
    @SerialName("auto_want_mode") val autoWantMode: String = "none",
    @SerialName("in_watchlist") val inWatchlist: Boolean = false,
    val cast: List<CastDto> = emptyList(),
    val crew: List<CrewDto> = emptyList(),
    @SerialName("continue_episode") val continueEpisode: ContinueEpisodeDto? = null,
    @SerialName("first_aired") val firstAired: String? = null,
    @SerialName("last_aired") val lastAired: String? = null,
    @SerialName("next_airing") val nextAiring: String? = null,
)

@Serializable
data class SeasonDto(
    val id: String,
    @SerialName("show_id") val showId: String? = null,
    @SerialName("season_identifier") val seasonIdentifier: String,
    @SerialName("season_sort_order") val seasonSortOrder: Int,
    val title: String? = null,
    val description: String? = null,
    @SerialName("poster_url") val posterUrl: String? = null,
    @SerialName("air_date") val airDate: String? = null,
    @SerialName("episode_count") val episodeCount: Int? = null,
    @SerialName("is_monitored") val isMonitored: Boolean = false,
    @SerialName("is_special") val isSpecial: Boolean = false,
    val episodes: List<EpisodeDto> = emptyList(),
)

@Serializable
data class EpisodeDto(
    val id: String,
    @SerialName("season_id") val seasonId: String? = null,
    @SerialName("episode_identifier") val episodeIdentifier: String,
    @SerialName("episode_sort_order") val episodeSortOrder: Int,
    val title: String? = null,
    val description: String? = null,
    @SerialName("still_url") val stillUrl: String? = null,
    @SerialName("air_date") val airDate: String? = null,
    val runtime: Int? = null,
    @SerialName("is_in_library") val isInLibrary: Boolean = false,
    @SerialName("want_status") val wantStatus: String = "",
    val watched: Boolean? = null,
    @SerialName("watch_position_seconds") val watchPositionSeconds: Int? = null,
)

@Serializable
data class PositionSaved(val watched: Boolean? = null)

@Serializable
data class PositionResponse(
    @SerialName("position_seconds") val positionSeconds: Int? = null,
    val watched: Boolean = false,
) {
    // A finished title keeps the position it was finished at, which is in its credits.
    val resumeSeconds: Int get() = if (watched) 0 else positionSeconds ?: 0
}

// -- Session DTOs --

@Serializable
data class CreateSessionRequest(
    @SerialName("media_type") val mediaType: String,
    /** The movie or episode. The server picks the file, so the client never asks for one. */
    @SerialName("media_id") val mediaId: String,
    val capabilities: ClientCapabilities,
    @SerialName("force_transcode") val forceTranscode: Boolean = false,
    @SerialName("max_bitrate_kbps") val maxBitrateKbps: Int? = null,
    @SerialName("preferred_audio_language") val preferredAudioLanguage: String? = null,
    @SerialName("preferred_audio_title") val preferredAudioTitle: String? = null,
    @SerialName("preferred_subtitle_language") val preferredSubtitleLanguage: String? = null,
    @SerialName("subtitles_enabled") val subtitlesEnabled: Boolean? = null,
    /**
     * Where to start producing from, in seconds.
     *
     * A transcode is built from wherever it was told to begin, so a resume has to be part
     * of creating the session rather than something asked of it afterwards — left at zero,
     * the only thing the client can do is seek into segments the server will not reach for
     * the length of the title.
     */
    @SerialName("start_time") val startTime: Int? = null,
)

@Serializable
data class PlaybackTokenDto(
    val token: String,
    @SerialName("expires_at") val expiresAt: String? = null,
)

@Serializable
data class ClientCapabilities(
    @SerialName("video_codecs") val videoCodecs: List<String>,
    @SerialName("audio_codecs") val audioCodecs: List<String>,
    val containers: List<String>,
    @SerialName("supports_interlaced") val supportsInterlaced: Boolean? = null,
    @SerialName("max_height") val maxHeight: Int? = null,
    @SerialName("hdr_formats") val hdrFormats: List<String>? = null,
)

@Serializable
data class SessionResponse(
    @SerialName("session_id") val sessionId: String,
    val method: String,
    @SerialName("playlist_url") val playlistUrl: String? = null,
    @SerialName("direct_url") val directUrl: String? = null,
    @SerialName("duration_seconds") val durationSeconds: Double = 0.0,
    @SerialName("thumbnails_url") val thumbnailsUrl: String? = null,
    val lufs: Double? = null,
    val chapters: List<SessionChapter> = emptyList(),
    @SerialName("skip_segments") val skipSegments: List<SkipSegment> = emptyList(),
    /** Where the timeline begins. See [rebaseSeconds] — an echo is not a rebase. */
    @SerialName("start_time") val startTime: Double? = null,
    @SerialName("audio_tracks") val audioTracks: List<AudioTrackDto> = emptyList(),
    val subtitles: List<PlaybackSubtitleTrackDto> = emptyList(),
    /** Stream index actually selected for playback (matches one of audio_tracks). */
    @SerialName("selected_audio_index") val selectedAudioIndex: Int? = null,
    @SerialName("preferred_audio_stream_index") val preferredAudioStreamIndex: Int? = null,
    /** Stream index actually selected for playback (matches one of subtitles). */
    @SerialName("selected_subtitle_index") val selectedSubtitleIndex: Int? = null,
    /** Media ID of the auto-created entity (set on debrid resolve with auto_create). */
    @SerialName("media_id") val mediaId: String? = null,
    /** The file this session plays. Skip-detection events are addressed to it. */
    @SerialName("file_id") val fileId: String? = null,
    /** Source video width — used for aspect-ratio bucket lookup. Null when unprobed. */
    @SerialName("video_width") val videoWidth: Int? = null,
    @SerialName("video_height") val videoHeight: Int? = null,
    val picture: PictureArea? = null,
)

/**
 * Where the server rebased this session's timeline to, if it did.
 *
 * Only a transcode is rebased: its playlist is produced from the resume point and serves
 * it as its own zero, so the client has only the residual to seek. A direct-played session
 * is the whole file from its start, and the playhead is this client's to move — but the
 * server echoes `start_time` back whatever it did with it, and taking that echo for a
 * rebase puts the player's zero 23 minutes into the title. The clock then reads right
 * while the picture sits at the top, the seek that would have moved it is skipped as
 * already applied, and every later seek lands short by the same amount.
 */
private fun SessionResponse.rebaseSeconds(): Int? =
    startTime?.toInt()?.takeIf { it > 0 && method != "direct_play" }

/**
 * The server's own account of whether a session can still deliver bytes.
 *
 * Split three ways on purpose: a request that never arrived says nothing about the
 * session, and treating that as a death is how a reconnect loop starts on a flaky link.
 */
sealed interface SessionStatus {
    /** Could not ask. No conclusion to draw. */
    data object Unknown : SessionStatus

    /** The server has no record of this session. */
    data object Missing : SessionStatus
}

@Serializable
data class OfflineExport(
    @SerialName("export_id") val exportId: String = "",
    val state: String,
    val progress: Double = 0.0,
    @SerialName("size_bytes") val sizeBytes: Long? = null,
    val error: String? = null,
    @SerialName("file_url") val fileUrl: String = "",
) {
    /** The server judged a copy at that quality no smaller than the title's own file. */
    val original: Boolean get() = state == "original"
    val ready: Boolean get() = state == "ready"
    val failed: Boolean get() = state == "failed"
}

@Serializable
data class SessionStatusDto(
    val state: String,
    val method: String? = null,
    @SerialName("is_live") val isLive: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    @SerialName("source_readable") val sourceReadable: Boolean = true,
    @SerialName("transcode_running") val transcodeRunning: Boolean? = null,
    @SerialName("segments_ready") val segmentsReady: Int? = null,
    val feed: SessionFeed? = null,
) : SessionStatus

/** What a debrid session has sent the player, and how its own download is going. */
@Serializable
data class SessionFeed(
    @SerialName("requests_open") val requestsOpen: Int = 0,
    @SerialName("bytes_served") val bytesServed: Long = 0,
    /** Null until the player has made its first request. */
    @SerialName("idle_ms") val idleMs: Long? = null,
    val source: String? = null,
    @SerialName("downloaded_bytes") val downloadedBytes: Long? = null,
    @SerialName("total_bytes") val totalBytes: Long? = null,
    @SerialName("download_bps") val downloadBps: Long? = null,
)

@Serializable
data class AudioTrackDto(
    @SerialName("stream_index") val streamIndex: Int,
    val codec: String = "",
    val channels: Int = 2,
    val language: String? = null,
    @SerialName("language_name") val languageName: String? = null,
    val title: String? = null,
    @SerialName("is_default") val isDefault: Boolean = false,
)

@Serializable
data class SessionChapter(
    val index: Int,
    val title: String? = null,
    @SerialName("start_seconds") val startSeconds: Double,
    @SerialName("end_seconds") val endSeconds: Double,
)

@Serializable
data class SkipSegment(
    @SerialName("segment_type") val segmentType: String, // intro, credits, post_credits_scene, recap
    @SerialName("start_seconds") val startSeconds: Double,
    @SerialName("end_seconds") val endSeconds: Double,
    val confidence: Double? = null,
    val source: String = "",
)

// -- Debrid streaming DTOs --

@Serializable
data class StreamOptionDto(
    val id: String,
    @SerialName("release_title") val releaseTitle: String,
    val size: Long = 0,
    val indexer: String = "",
    @SerialName("info_hash") val infoHash: String? = null,
    val quality: kotlinx.serialization.json.JsonObject? = null,
    @SerialName("total_score") val totalScore: Double = 0.0,
    @SerialName("match_score") val matchScore: Double = 0.0,
    @SerialName("quality_score") val qualityScore: Double = 0.0,
    @SerialName("debrid_provider") val debridProvider: String,
    @SerialName("debrid_provider_id") val debridProviderId: String,
    val cached: Boolean = false,
    @SerialName("source_type") val sourceType: String = "torrent",
    val files: List<StreamFileDto> = emptyList(),
    @SerialName("publish_date") val publishDate: String? = null,
    val playback: String? = null,
    val availability: String? = null,
    val group: String? = null,
    @SerialName("audio_languages") val audioLanguages: List<String> = emptyList(),
)

@Serializable
data class StreamFileDto(
    val id: Int,
    val path: String = "",
    val size: Long = 0,
)

@Serializable
data class StreamSearchResponseDto(
    val options: List<StreamOptionDto> = emptyList(),
    val errors: List<String> = emptyList(),
    @SerialName("preferred_info_hash") val preferredInfoHash: String? = null,
    @SerialName("preferred_file_id") val preferredFileId: Int? = null,
)

@Serializable
data class StreamResolveStatusResponseDto(
    val status: String,
    @SerialName("stream_url") val streamUrl: String? = null,
    val filename: String? = null,
    val filesize: Long? = null,
    val progress: Double? = null,
    val message: String? = null,
)

@Serializable
data class DiscoverSeasonDto(
    @SerialName("season_number") val seasonNumber: Int,
    val title: String? = null,
    @SerialName("episode_count") val episodeCount: Int? = null,
    @SerialName("air_date") val airDate: String? = null,
)

@Serializable
data class DiscoverEpisodeDto(
    @SerialName("episode_number") val episodeNumber: Int,
    val title: String? = null,
    @SerialName("air_date") val airDate: String? = null,
    val runtime: Int? = null,
    @SerialName("still_url") val stillUrl: String? = null,
    val description: String? = null,
)

@Serializable
data class DiscoverSeasonDetailDto(
    val episodes: List<DiscoverEpisodeDto> = emptyList(),
)

@Serializable
data class LibraryStatsDto(
    @SerialName("kobload_configured") val kobloadConfigured: Boolean = false,
)

@Serializable
data class AdminConfigDto(
    @SerialName("cast_receiver_app_id") val castReceiverAppId: String? = null,
)

/** Discriminator-only DTO for /admin/stream/resolve — the response is either a
 *  full SessionResponse (when [sessionId] is set) or a caching handle (when
 *  [debridProviderId]/[torrentId] are set). */
@Serializable
data class StreamResolveDiscriminatorDto(
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("debrid_provider_id") val debridProviderId: String? = null,
    @SerialName("torrent_id") val torrentId: String? = null,
)

// VLC can play virtually anything — request capabilities that ensure DirectPlay
// Codec names must match ffprobe output exactly (kobbler stores them as-is)
private val VLC_CAPABILITIES = ClientCapabilities(
    videoCodecs = listOf("h264", "hevc", "h265", "vp8", "vp9", "av1", "mpeg2video", "mpeg4", "wmv3", "vc1"),
    // mp2 is the primary audio on most SD DVB-T services, and leaving it out was
    // costing a needless server-side encode on 32 of the tuner's channels. The
    // shipped libVLC carries ff_mp2_decoder like the rest of FFmpeg.
    audioCodecs = listOf("aac", "ac3", "eac3", "e-ac-3", "dts", "flac", "mp2", "mp3", "opus", "vorbis", "truehd", "pcm_s16le", "pcm_s24le"),
    containers = listOf("mp4", "mkv", "matroska,webm", "matroska", "webm", "avi", "ts", "mov", "mov,mp4,m4a,3gp,3g2,mj2"),
)

// -- Book detail DTO --

@Serializable
data class BookDetailDto(
    val id: String,
    val title: String,
    val authors: List<AuthorRef> = emptyList(),
    val description: String? = null,
    val publisher: String? = null,
    @SerialName("cover_url") val coverUrl: String? = null,
    val formats: List<String> = emptyList(),
    val rating: Double? = null,
    val narrator: String? = null,
    @SerialName("duration_seconds") val durationSeconds: Double? = null,
    @SerialName("has_audiobook") val hasAudiobook: Boolean = false,
    @SerialName("page_count") val pageCount: Int? = null,
    val genres: List<String> = emptyList(),
    @SerialName("series_id") val seriesId: String? = null,
    @SerialName("series_name") val seriesName: String? = null,
    @SerialName("series_index") val seriesIndex: Double? = null,
    @SerialName("is_in_library") val isInLibrary: Boolean? = null,
    @SerialName("want_status") val wantStatus: String? = null,
    @SerialName("series_memberships") val seriesMemberships: List<BookSeriesEntryDto> = emptyList(),
)

@Serializable
data class BookSeriesEntryDto(
    @SerialName("series_id") val seriesId: String,
    @SerialName("series_name") val seriesName: String,
    @SerialName("series_index") val seriesIndex: Double? = null,
)

@Serializable
data class AuthorRef(val id: String, val name: String)

// -- Audiobook DTOs --

@Serializable
data class AudioFileDto(
    val id: String,
    @SerialName("file_index") val fileIndex: Int,
    val format: String,
    @SerialName("file_name") val fileName: String,
    @SerialName("duration_seconds") val durationSeconds: Double? = null,
    val available: Boolean = true,
)

@Serializable
data class UnifiedChapterDto(
    val id: String,
    @SerialName("chapter_index") val chapterIndex: Int,
    val title: String? = null,
    @SerialName("start_seconds") val startSeconds: Double,
    @SerialName("end_seconds") val endSeconds: Double,
    @SerialName("file_id") val fileId: String,
    val source: String? = null,
    @SerialName("global_start_seconds") val globalStartSeconds: Double = startSeconds,
    @SerialName("global_end_seconds") val globalEndSeconds: Double = endSeconds,
)

@Serializable
data class AudioPositionDto(
    @SerialName("file_id") val fileId: String? = null,
    @SerialName("position_seconds") val positionSeconds: Double? = null,
    @SerialName("progress_percent") val progressPercent: Double? = null,
)

@Serializable
data class TranscriptSegmentDto(
    val id: String,
    @SerialName("file_id") val fileId: String,
    @SerialName("segment_index") val segmentIndex: Int,
    @SerialName("start_seconds") val startSeconds: Double,
    @SerialName("end_seconds") val endSeconds: Double,
    val text: String,
)

@Serializable
data class ReadAlongDataDto(
    @SerialName("sync_points") val syncPoints: List<SyncPointDto> = emptyList(),
    @SerialName("transcript_segments") val transcriptSegments: List<TranscriptSegmentDto> = emptyList(),
    @SerialName("audio_file_ids") val audioFileIds: List<String> = emptyList(),
    @SerialName("has_ebook_segments") val hasEbookSegments: Boolean = false,
)

@Serializable
data class SyncPointDto(
    @SerialName("audio_file_id") val audioFileId: String,
    @SerialName("audio_seconds") val audioSeconds: Double,
    @SerialName("ebook_file_id") val ebookFileId: String? = null,
    @SerialName("ebook_cfi") val ebookCfi: String? = null,
    val confidence: Double? = null,
    val position: Position? = null,
)

// -- Reader DTOs --

@Serializable
data class ReaderProgressDto(
    val cfi: String? = null,
    @SerialName("progress_percent") val progressPercent: Double? = null,
    val position: Position? = null,
)

// -- TTS DTOs --

@Serializable
data class TtsStatusDto(
    val enabled: Boolean = false,
    val voices: List<TtsVoiceDto> = emptyList(),
    @SerialName("supports_emotion") val supportsEmotion: Boolean = false,
)

@Serializable
data class TtsVoiceDto(val id: String, val name: String)

/**
 * Body for `/api/v1/admin/tts/synthesize`.
 *
 * `speed` renders SERVER-SIDE, so the PCM arrives already at rate and the word
 * timings are already in that timebase — nothing is rescaled on the way to the
 * sink. It is sent explicitly even at 1.0, because kobbler falls back to the
 * active preset's stored speed when the field is absent. The rate that was
 * actually rendered comes back on `paragraph_start`, and is not necessarily the
 * one that was asked for.
 */
@Serializable
data class TtsSynthesizeRequest(
    val text: String,
    val voice: String? = null,
    val speed: Double? = null,
    @SerialName("book_id") val bookId: String? = null,
)

/**
 * Decoded event from `/api/v1/admin/tts/synthesize`.
 *
 * Backend emits exactly one envelope shape per request (chosen by config):
 *  - Per-sentence MP3 path → `Sentence` + `Done`
 *  - streamed paragraph path → `ParagraphStart` + N×`AudioPcm` + N×`Chunk` +
 *    N×`AlignmentPartial` + `AlignmentFinal`, repeated per paragraph, then `Done`
 *  - Either path may emit `Error`
 *  - Anything we don't recognise is `Other` so the stream loop never breaks
 */
sealed interface TtsStreamEvent {
    /** MP3 sentence chunk — legacy per-sentence path (chatterbox / kokoro / openai). */
    data class Sentence(
        val index: Int,
        val text: String,
        val audioB64: String,
        /** Rate this sentence was RENDERED at — each one is its own render. */
        val speed: Double,
        /** Where the sentence sits in a cast render's stream, which carries no audio here. */
        val audioOffsetMs: Int? = null,
        val durationMs: Int? = null,
    ) : TtsStreamEvent

    /** Start of a paragraph in the streaming path. `audioOffsetMs` is global; word_offset is global word index. */
    data class ParagraphStart(
        val index: Int,
        val text: String,
        val wordOffset: Int,
        val audioOffsetMs: Int,
        val sampleRate: Int,
        /**
         * The rate the server actually RENDERED at, which is not necessarily the
         * one that was asked for — an engine that can't do the requested rate
         * renders at 1.0 instead and says so here. The audio that follows is
         * already at this rate.
         */
        val speed: Double,
    ) : TtsStreamEvent

    /** Raw 16-bit-LE PCM bytes for a paragraph (streaming path). */
    data class AudioPcm(val paragraphIndex: Int, val pcm: ByteArray) : TtsStreamEvent

    /**
     * A chunk anchor: the engine is about to speak [wordCount] words starting
     * [wordOffset] words and [audioOffsetMs] milliseconds into the read.
     *
     * No aligner involved — the engine already knew where its own sentence
     * groups begin — so these arrive seconds before the first word timings and
     * keep arriving when alignment is off entirely. Offsets are global to the
     * whole read, like [ParagraphStart]'s.
     */
    data class Chunk(
        val paragraphIndex: Int,
        val index: Int,
        val chunksTotal: Int,
        val wordOffset: Int,
        val wordCount: Int,
        val audioOffsetMs: Int,
        val text: String,
    ) : TtsStreamEvent

    /** In-progress forced-alignment update for a paragraph; words list overrides previous by word_index. */
    data class AlignmentPartial(
        val paragraphIndex: Int,
        val audioSeenMs: Int,
        val words: List<TtsWordTiming>,
    ) : TtsStreamEvent

    /** Final locked alignment for a paragraph. */
    data class AlignmentFinal(
        val paragraphIndex: Int,
        val audioTotalMs: Int,
        val words: List<TtsWordTiming>,
    ) : TtsStreamEvent

    /** Backend-side error. `fatal=true` means the whole synth bailed. */
    data class Error(val paragraphIndex: Int?, val message: String, val fatal: Boolean) : TtsStreamEvent

    /** Closing event. */
    data object Done : TtsStreamEvent

    data object Other : TtsStreamEvent
}

@Serializable
data class TtsWordTiming(
    @SerialName("word_index") val wordIndex: Int,
    val text: String,
    @SerialName("t0_ms") val t0Ms: Int,
    @SerialName("t1_ms") val t1Ms: Int,
)

// -- Activity feed DTO --

@Serializable
data class PaginatedActivity(
    val items: List<ActivityEventDto>,
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("total_count") val totalCount: Long? = null,
)

@Serializable
data class ActivityEventDto(
    val id: String,
    @SerialName("event_kind") val eventKind: String,
    @SerialName("entity_type") val entityType: String? = null,
    @SerialName("entity_id") val entityId: String? = null,
    val title: String,
    val subtitle: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    val url: String? = null,
    @SerialName("created_at") val createdAt: String,
)

// -- Books list DTO --

@Serializable
data class PaginatedBooks(
    val items: List<BookListDto>,
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("prev_cursor") val prevCursor: String? = null,
    @SerialName("total_count") val totalCount: Long? = null,
)

@Serializable
data class BookListDto(
    val id: String,
    val title: String,
    val authors: List<AuthorRef> = emptyList(),
    @SerialName("cover_url") val coverUrl: String? = null,
    val formats: List<String> = emptyList(),
    @SerialName("has_audiobook") val hasAudiobook: Boolean = false,
)

// -- Authors list DTO --

@Serializable
data class PaginatedAuthors(
    val items: List<AuthorSummaryDto>,
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("total_count") val totalCount: Long? = null,
)

@Serializable
data class AuthorSummaryDto(
    val id: String,
    val name: String,
    @SerialName("book_count") val bookCount: Long = 0,
    @SerialName("series_count") val seriesCount: Long = 0,
    @SerialName("cover_urls") val coverUrls: List<String> = emptyList(),
    @SerialName("image_url") val imageUrl: String? = null,
    val bio: String? = null,
    @SerialName("top_series_names") val topSeriesNames: String? = null,
)

// -- Series list DTO --

@Serializable
data class PaginatedSeries(
    val items: List<SeriesSummaryDto>,
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("total_count") val totalCount: Long? = null,
)

@Serializable
data class SeriesSummaryDto(
    val id: String,
    val name: String,
    @SerialName("book_count") val bookCount: Long = 0,
    val authors: List<AuthorRef> = emptyList(),
    @SerialName("cover_urls") val coverUrls: List<String> = emptyList(),
    @SerialName("image_url") val imageUrl: String? = null,
    val description: String? = null,
)

// -- Library book DTO (used by author/series book lists) --

@Serializable
data class LibraryBookDto(
    val id: String,
    val title: String,
    val authors: List<AuthorRef> = emptyList(),
    @SerialName("cover_url") val coverUrl: String? = null,
    val formats: List<String> = emptyList(),
    @SerialName("has_audiobook") val hasAudiobook: Boolean = false,
    @SerialName("series_id") val seriesId: String? = null,
    @SerialName("series_name") val seriesName: String? = null,
    @SerialName("series_index") val seriesIndex: Double? = null,
    val rating: Double? = null,
)

// -- Movies list DTO --

@Serializable
data class PaginatedMovies(
    val items: List<MovieListDto>,
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("prev_cursor") val prevCursor: String? = null,
    @SerialName("total_count") val totalCount: Long? = null,
)

@Serializable
data class MovieListDto(
    val id: String,
    val title: String,
    @SerialName("sort_title") val sortTitle: String? = null,
    val year: Int? = null,
    @SerialName("poster_url") val posterUrl: String? = null,
    @SerialName("backdrop_url") val backdropUrl: String? = null,
    @SerialName("logo_url") val logoUrl: String? = null,
    @SerialName("is_in_library") val isInLibrary: Boolean = false,
    @SerialName("want_status") val wantStatus: String = "",
)

@Serializable
data class GenreCount(
    val name: String,
    val count: Int,
    @SerialName("backdrop_url") val backdropUrl: String? = null,
)

@Serializable
data class GenresResponse(val genres: List<GenreCount>)

// -- Shows list DTO --

@Serializable
data class PaginatedShows(
    val items: List<ShowListDto>,
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("prev_cursor") val prevCursor: String? = null,
    @SerialName("total_count") val totalCount: Long? = null,
)

@Serializable
data class ShowListDto(
    val id: String,
    val title: String,
    @SerialName("sort_title") val sortTitle: String? = null,
    val year: Int? = null,
    @SerialName("poster_url") val posterUrl: String? = null,
    @SerialName("backdrop_url") val backdropUrl: String? = null,
    @SerialName("logo_url") val logoUrl: String? = null,
    val status: String? = null,
    val network: String? = null,
    @SerialName("content_category") val contentCategory: String? = null,
)

// -- Calendar DTOs --

@Serializable
data class CalendarResponse(val items: List<CalendarItemDto>)

@Serializable
data class CalendarItemDto(
    val id: String,
    @SerialName("media_type") val mediaType: String,
    val date: String,
    val title: String,
    val subtitle: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    val link: String,
)

// -- Search DTOs --

@Serializable
data class SearchSuggestion(
    val title: String,
    @SerialName("media_type") val mediaType: String,
    val source: String,
)

@Serializable
data class SearchSuggestionsResponse(val suggestions: List<SearchSuggestion> = emptyList())

@Serializable
data class SearchResponse(
    @SerialName("top_hits") val topHits: List<SearchHitDto> = emptyList(),
    val remaining: List<SearchHitDto> = emptyList(),
)

@Serializable
data class SearchHitDto(
    val type: String,
    val id: String,
    val title: String? = null,
    val label: String? = null,
    val name: String? = null,
    val authors: List<String>? = null,
    val year: Int? = null,
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("poster_url") val posterUrl: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("still_url") val stillUrl: String? = null,
    @SerialName("logo_url") val logoUrl: String? = null,
    val network: String? = null,
    val path: String? = null,
    val kind: String? = null,
    // TV season/episode fields from server
    @SerialName("display_title") val displayTitle: String? = null,
    @SerialName("show_id") val showId: String? = null,
    @SerialName("show_title") val showTitle: String? = null,
    @SerialName("episode_title") val episodeTitle: String? = null,
    @SerialName("episode_code") val episodeCode: String? = null,
    @SerialName("season_code") val seasonCode: String? = null,
    @SerialName("season_identifier") val seasonIdentifier: String? = null,
)

// -- Person DTOs --

@Serializable
data class PersonDto(
    val id: String,
    val name: String,
    @SerialName("sort_name") val sortName: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    val bio: String? = null,
    @SerialName("birth_date") val birthDate: String? = null,
    @SerialName("death_date") val deathDate: String? = null,
    @SerialName("known_for_department") val knownForDepartment: String? = null,
    @SerialName("place_of_birth") val placeOfBirth: String? = null,
)

@Serializable
data class FilmographyCreditDto(
    @SerialName("provider_id") val providerId: String,
    @SerialName("media_type") val mediaType: String,
    val title: String,
    val year: Int? = null,
    @SerialName("poster_url") val posterUrl: String? = null,
    val character: String? = null,
    val department: String? = null,
    val job: String? = null,
    @SerialName("credit_type") val creditType: String,
    @SerialName("in_library") val inLibrary: Boolean = false,
    @SerialName("library_id") val libraryId: String? = null,
    @SerialName("want_status") val wantStatus: String? = null,
    val dismissed: Boolean = false,
)

@Serializable
data class FilmographyResponseDto(val credits: List<FilmographyCreditDto>)

// -- Wanted DTOs --

@Serializable
data class WantedItemDto(
    val id: String,
    val title: String,
    val author: String? = null,
    val series: String? = null,
    @SerialName("series_index") val seriesIndex: Double? = null,
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("want_status") val wantStatus: String,
    @SerialName("attempt_status") val attemptStatus: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class WantedListResponse(
    val items: List<WantedItemDto>,
    @SerialName("status_counts") val statusCounts: kotlinx.serialization.json.JsonObject? = null,
)

// -- IPTV DTOs --

@Serializable
data class IptvChannelDto(
    val id: String,
    val name: String,
    @SerialName("normalized_name") val normalizedName: String? = null,
    val language: String? = null,
    val country: String? = null,
    val category: String? = null,
    @SerialName("icon_url") val iconUrl: String? = null,
    val enabled: Boolean = true,
    @SerialName("display_order") val displayOrder: Int = 0,
    @SerialName("source_count") val sourceCount: Long = 0,
)

@Serializable
data class IptvEpgEntryDto(
    val id: String,
    @SerialName("channel_id") val channelId: String,
    val title: String? = null,
    val description: String? = null,
    @SerialName("start_time") val startTime: String,
    @SerialName("stop_time") val stopTime: String,
    val categories: List<String> = emptyList(),
)

@Serializable
data class IptvGroupDto(
    val id: String,
    val name: String,
    val slug: String,
    val icon: String? = null,
    @SerialName("display_order") val displayOrder: Int = 0,
    @SerialName("channel_count") val channelCount: Long = 0,
    val enabled: Boolean = true,
)

@Serializable
data class IptvNowNextDto(
    @SerialName("channel_id") val channelId: String,
    val now: IptvEpgEntryDto? = null,
    val next: IptvEpgEntryDto? = null,
)

@Serializable
data class IptvLiveSessionDto(
    @SerialName("session_id") val sessionId: String,
    val method: String? = null,
    @SerialName("playlist_url") val playlistUrl: String? = null,
    @SerialName("direct_url") val directUrl: String? = null,
)

@Serializable
data class IptvFavouriteDto(
    val id: String,
    @SerialName("channel_id") val channelId: String,
    @SerialName("display_order") val displayOrder: Int = 0,
)

@Serializable
data class IptvChannelListResponse(
    val items: List<IptvChannelDto> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class IptvEpgFavouritesResponse(
    val grid: List<IptvEpgFavouriteChannel> = emptyList(),
)

@Serializable
data class IptvEpgFavouriteChannel(
    val channel: IptvEpgFavChannelInfo,
    val programs: List<IptvEpgEntryDto> = emptyList(),
)

@Serializable
data class IptvEpgFavChannelInfo(
    val id: String,
    val name: String,
    @SerialName("icon_url") val iconUrl: String? = null,
    val category: String? = null,
)

@Serializable
data class IptvWatchMatchDto(
    @SerialName("watch_id") val watchId: String,
    @SerialName("channel_id") val channelId: String,
    @SerialName("channel_name") val channelName: String,
    @SerialName("channel_icon_url") val channelIconUrl: String? = null,
    @SerialName("epg_title") val epgTitle: String,
    @SerialName("start_time") val startTime: String,
    @SerialName("stop_time") val stopTime: String,
    @SerialName("is_live") val isLive: Boolean = false,
)

const val LiveAdPostureShade = "shade"
const val LiveAdPostureButton = "button"
const val LiveAdPostureAuto = "auto"

/**
 * The posture arrives already resolved on the recording payload. The settings
 * endpoint it used to come from is admin-only, so a contributor read the owner's
 * policy as the default and never as `auto`.
 */
fun resolveAdPosture(value: String?): String = when (value) {
    LiveAdPostureShade, LiveAdPostureButton, LiveAdPostureAuto -> value
    else -> LiveAdPostureShade
}

/** Ad skip collapses on news and live sport, and the server says which those are. */
fun shouldAutoSkipAd(posture: String, adSkipDisabled: Boolean): Boolean =
    posture == LiveAdPostureAuto && !adSkipDisabled

private fun appendSessionParam(url: String, sessionId: String): String =
    if (url.contains('?')) "$url&session=$sessionId" else "$url?session=$sessionId"

// -- Live TV (tuner) DTOs --

@Serializable
data class LiveTunerDto(
    val id: String,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("friendly_name") val friendlyName: String? = null,
    @SerialName("model_number") val modelNumber: String? = null,
    @SerialName("firmware_version") val firmwareVersion: String? = null,
    @SerialName("base_url") val baseUrl: String? = null,
    @SerialName("tuner_count") val tunerCount: Int = 0,
    @SerialName("last_scan_at") val lastScanAt: String? = null,
    @SerialName("last_guide_sync_at") val lastGuideSyncAt: String? = null,
    @SerialName("channel_count") val channelCount: Int = 0,
    @SerialName("enabled_channel_count") val enabledChannelCount: Int = 0,
    @SerialName("leases_in_use") val leasesInUse: Int = 0,
)

@Serializable
data class LiveTunerListResponse(val items: List<LiveTunerDto> = emptyList())

@Serializable
data class LiveChannelDto(
    val id: String,
    @SerialName("tuner_id") val tunerId: String? = null,
    val lcn: Int? = null,
    @SerialName("scan_name") val scanName: String? = null,
    val callsign: String? = null,
    @SerialName("video_codec") val videoCodec: String? = null,
    @SerialName("audio_codec") val audioCodec: String? = null,
    val hd: Boolean = false,
    @SerialName("signal_strength") val signalStrength: Int? = null,
    @SerialName("signal_quality") val signalQuality: Int? = null,
    val enabled: Boolean = true,
    val favourite: Boolean = false,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("logo_url") val logoUrl: String? = null,
    val kind: String? = null,
    val region: String? = null,
    val network: String? = null,
    val name: String = "",
    @SerialName("mux_label") val muxLabel: String? = null,
    @SerialName("type_label") val typeLabel: String? = null,
    @SerialName("has_guide") val hasGuide: Boolean = false,
    @SerialName("guide_entry_count") val guideEntryCount: Int = 0,
    val now: LiveGuideNowDto? = null,
)

@Serializable
data class LiveFacetDto(val value: String, val label: String = "", val count: Int = 0)

@Serializable
data class LiveFacetsDto(
    val network: List<LiveFacetDto> = emptyList(),
    val region: List<LiveFacetDto> = emptyList(),
    val type: List<LiveFacetDto> = emptyList(),
    val guide: List<LiveFacetDto> = emptyList(),
    val status: List<LiveFacetDto> = emptyList(),
    val mux: List<LiveFacetDto> = emptyList(),
)

@Serializable
data class LiveChannelListResponse(
    val items: List<LiveChannelDto> = emptyList(),
    val total: Int = 0,
    val facets: LiveFacetsDto = LiveFacetsDto(),
)

@Serializable
data class LiveRecordingRefDto(val id: String, val status: String = "")

@Serializable
data class LiveGuideEntryDto(
    val id: String,
    @SerialName("channel_id") val channelId: String = "",
    @SerialName("start_time") val startTime: String,
    @SerialName("end_time") val endTime: String,
    val title: String = "",
    @SerialName("episode_number") val episodeNumber: String? = null,
    @SerialName("episode_title") val episodeTitle: String? = null,
    val synopsis: String? = null,
    @SerialName("original_airdate") val originalAirdate: String? = null,
    @SerialName("series_id") val seriesId: String? = null,
    val genres: List<String> = emptyList(),
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("poster_url") val posterUrl: String? = null,
    @SerialName("is_first") val isFirst: Boolean = false,
    @SerialName("episode_key") val episodeKey: String? = null,
    @SerialName("episode_key_kind") val episodeKeyKind: String? = null,
    @SerialName("duration_seconds") val durationSeconds: Long = 0,
    @SerialName("progress_pct") val progressPct: Float? = null,
    val recording: LiveRecordingRefDto? = null,
    @SerialName("repeat_count") val repeatCount: Int = 0,
)

@Serializable
data class LiveGuideChannelDto(
    val channel: LiveChannelDto,
    val entries: List<LiveGuideEntryDto> = emptyList(),
)

@Serializable
data class LiveGuideResponse(val channels: List<LiveGuideChannelDto> = emptyList())

@Serializable
data class LiveGuideNowDto(
    val channel: LiveChannelDto? = null,
    val now: LiveGuideEntryDto? = null,
    val next: LiveGuideEntryDto? = null,
    @SerialName("minutes_left") val minutesLeft: Int? = null,
)

@Serializable
data class LiveGuideNowResponse(val items: List<LiveGuideNowDto> = emptyList())

@Serializable
data class LiveOnNowItemDto(
    val channel: LiveChannelDto,
    val guide: LiveGuideEntryDto,
    @SerialName("minutes_left") val minutesLeft: Int? = null,
    @SerialName("starts_in_minutes") val startsInMinutes: Int? = null,
    val reason: String = "",
    val confidence: Float? = null,
)

@Serializable
data class LiveOnNowGroupDto(
    val key: String = "",
    val title: String = "",
    val subtitle: String = "",
    val items: List<LiveOnNowItemDto> = emptyList(),
)

@Serializable
data class LiveClashNoticeDto(
    val guide: LiveGuideEntryDto,
    val reason: String = "",
    val alternative: LiveGuideEntryDto? = null,
)

@Serializable
data class LiveOnNowResponse(
    @SerialName("generated_at") val generatedAt: String = "",
    @SerialName("tuner_notice") val tunerNotice: LiveClashNoticeDto? = null,
    val groups: List<LiveOnNowGroupDto> = emptyList(),
)

@Serializable
data class LiveLeaseDto(
    val id: String,
    @SerialName("tuner_id") val tunerId: String? = null,
    @SerialName("channel_id") val channelId: String? = null,
    @SerialName("channel_name") val channelName: String? = null,
    @SerialName("tuner_index") val tunerIndex: Int? = null,
    @SerialName("holder_kind") val holderKind: String = "",
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("recording_id") val recordingId: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("acquired_at") val acquiredAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
)

@Serializable
data class LiveLeaseListResponse(
    val total: Int = 0,
    @SerialName("in_use") val inUse: Int = 0,
    val items: List<LiveLeaseDto> = emptyList(),
)

@Serializable
data class LiveAdBreakDto(
    val id: String,
    @SerialName("recording_id") val recordingId: String = "",
    @SerialName("start_seconds") val startSeconds: Double = 0.0,
    @SerialName("end_seconds") val endSeconds: Double = 0.0,
    val method: String? = null,
    val confidence: Float? = null,
    @SerialName("ads_known") val adsKnown: Int = 0,
    @SerialName("ads_total") val adsTotal: Int = 0,
    @SerialName("corrected_at") val correctedAt: String? = null,
)

@Serializable
data class LiveProvenanceDto(
    @SerialName("channel_name") val channelName: String? = null,
    val lcn: Int? = null,
    val aired: String? = null,
)

@Serializable
data class LiveRecordingDto(
    val id: String,
    @SerialName("channel_id") val channelId: String? = null,
    @SerialName("channel_name") val channelName: String? = null,
    @SerialName("guide_id") val guideId: String? = null,
    @SerialName("series_rule_id") val seriesRuleId: String? = null,
    val title: String = "",
    @SerialName("episode_number") val episodeNumber: String? = null,
    @SerialName("episode_title") val episodeTitle: String? = null,
    @SerialName("episode_key") val episodeKey: String? = null,
    @SerialName("series_id") val seriesId: String? = null,
    val synopsis: String? = null,
    @SerialName("scheduled_start") val scheduledStart: String? = null,
    @SerialName("scheduled_end") val scheduledEnd: String? = null,
    @SerialName("actual_start") val actualStart: String? = null,
    @SerialName("actual_end") val actualEnd: String? = null,
    @SerialName("padding_before_secs") val paddingBeforeSecs: Int = 0,
    @SerialName("padding_after_secs") val paddingAfterSecs: Int = 0,
    val status: String = "",
    @SerialName("tuner_index") val tunerIndex: Int? = null,
    @SerialName("duration_seconds") val durationSeconds: Double? = null,
    @SerialName("video_codec") val videoCodec: String? = null,
    @SerialName("audio_codec") val audioCodec: String? = null,
    val container: String? = null,
    @SerialName("size_bytes") val sizeBytes: Long? = null,
    @SerialName("episode_id") val episodeId: String? = null,
    @SerialName("movie_id") val movieId: String? = null,
    @SerialName("moved_to_guide_id") val movedToGuideId: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    val error: String? = null,
    @SerialName("is_in_progress") val isInProgress: Boolean = false,
    @SerialName("seconds_on_disk") val secondsOnDisk: Double = 0.0,
    @SerialName("playable_from_start") val playableFromStart: Boolean = false,
    @SerialName("ad_breaks") val adBreaks: List<LiveAdBreakDto> = emptyList(),
    val provenance: LiveProvenanceDto? = null,
    @SerialName("ad_posture") val adPosture: String? = null,
    @SerialName("ad_skip_disabled") val adSkipDisabled: Boolean = false,
)

@Serializable
data class LiveRecordingListResponse(
    val items: List<LiveRecordingDto> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class LiveSeriesRuleDto(
    val id: String,
    @SerialName("series_id") val seriesId: String? = null,
    val title: String = "",
    val enabled: Boolean = true,
    @SerialName("channel_ids") val channelIds: List<String> = emptyList(),
    val weekdays: List<Int> = emptyList(),
    @SerialName("window_start") val windowStart: String? = null,
    @SerialName("window_end") val windowEnd: String? = null,
    @SerialName("new_only") val newOnly: Boolean = true,
    @SerialName("min_season") val minSeason: Int? = null,
    @SerialName("keep_count") val keepCount: Int? = null,
    @SerialName("keep_until") val keepUntil: String? = null,
    @SerialName("keep_until_date") val keepUntilDate: String? = null,
    @SerialName("padding_before_secs") val paddingBeforeSecs: Int = 0,
    @SerialName("padding_after_secs") val paddingAfterSecs: Int = 0,
    @SerialName("on_clash") val onClash: String = "move",
    val priority: Int = 0,
    @SerialName("queued_count") val queuedCount: Int = 0,
)

@Serializable
data class LiveSeriesRuleListResponse(val items: List<LiveSeriesRuleDto> = emptyList())

@Serializable
data class LiveClusterMemberDto(
    @SerialName("guide_id") val guideId: String = "",
    @SerialName("channel_id") val channelId: String = "",
    @SerialName("channel_name") val channelName: String = "",
    val lcn: Int? = null,
    val hd: Boolean = false,
    @SerialName("start_time") val startTime: String = "",
    val action: String = "skip",
    val reason: String = "",
)

@Serializable
data class LiveClusterDto(
    val label: String = "",
    @SerialName("start_time") val startTime: String = "",
    val members: List<LiveClusterMemberDto> = emptyList(),
)

@Serializable
data class LiveSessionDto(
    @SerialName("session_id") val sessionId: String,
    @SerialName("channel_id") val channelId: String = "",
    val method: String? = null,
    @SerialName("playlist_url") val playlistUrl: String? = null,
    @SerialName("direct_url") val directUrl: String? = null,
    @SerialName("video_codec") val videoCodec: String? = null,
    @SerialName("audio_codec") val audioCodec: String? = null,
    val hd: Boolean = false,
    val interlaced: Boolean? = null,
    @SerialName("thumbnails_url") val thumbnailsUrl: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    @SerialName("buffer_start_time") val bufferStartTime: String? = null,
    @SerialName("is_live") val isLive: Boolean = true,
    @SerialName("duration_seconds") val durationSeconds: Double = 0.0,
    val lease: LiveLeaseDto? = null,
    @SerialName("tuners_in_use") val tunersInUse: Int = 0,
    @SerialName("tuner_count") val tunerCount: Int = 0,
    val now: LiveGuideEntryDto? = null,
    val next: LiveGuideEntryDto? = null,
)

@Serializable
data class LiveBufferDto(
    @SerialName("start_time") val startTime: String? = null,
    @SerialName("end_time") val endTime: String? = null,
    @SerialName("live_edge_seconds") val liveEdgeSeconds: Double = 0.0,
    @SerialName("duration_seconds") val durationSeconds: Double = 0.0,
    @SerialName("retained_seconds") val retainedSeconds: Double = 0.0,
    @SerialName("cap_seconds") val capSeconds: Double = 0.0,
    @SerialName("tune_error") val tuneError: String? = null,
)

@Serializable
data class LiveRulePreviewResponse(
    val clusters: List<LiveClusterDto> = emptyList(),
    @SerialName("would_record") val wouldRecord: Int = 0,
    val suppressed: Int = 0,
)

// -- Notification DTOs --

@Serializable
data class NotificationDto(
    val id: String,
    val title: String,
    val message: String,
    @SerialName("entity_type") val entityType: String? = null,
    @SerialName("entity_id") val entityId: String? = null,
    @SerialName("entity_url") val entityUrl: String? = null,
    val read: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class NotificationListResponse(
    val notifications: List<NotificationDto> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class UnreadCountResponse(
    val count: Int = 0,
)

// -- Recent Streams DTOs --

@Serializable
data class RecentStreamDto(
    val id: String,
    @SerialName("media_type") val mediaType: String,
    @SerialName("media_id") val mediaId: String,
    @SerialName("release_title") val releaseTitle: String,
    @SerialName("debrid_provider") val debridProvider: String,
    val filename: String,
    val filesize: Long = 0,
    @SerialName("progress_seconds") val progressSeconds: Double? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("media_title") val mediaTitle: String? = null,
    @SerialName("media_image_url") val mediaImageUrl: String? = null,
)

// -- Subtitle Search DTOs --

@Serializable
data class SubtitleSearchResultDto(
    val provider: String,
    val id: String,
    val language: String,
    val format: String? = null,
    @SerialName("is_forced") val isForced: Boolean = false,
    @SerialName("is_hearing_impaired") val isHearingImpaired: Boolean = false,
    val rating: Double? = null,
    @SerialName("download_count") val downloadCount: Long? = null,
)

@Serializable
data class PlaybackSubtitleTrackDto(
    @SerialName("track_id") val trackId: String? = null,
    @SerialName("stream_index") val streamIndex: Int? = null,
    val codec: String? = null,
    val language: String? = null,
    @SerialName("language_name") val languageName: String? = null,
    val title: String? = null,
    val url: String? = null,
    val format: String? = null,
    @SerialName("is_default") val isDefault: Boolean = false,
    @SerialName("is_forced") val isForced: Boolean = false,
    @SerialName("is_hearing_impaired") val isHearingImpaired: Boolean = false,
)

// -- Discover DTOs --

@Serializable
internal data class CreateDiscoverSeasonWant(@SerialName("show_id") val showId: String)

@Serializable
data class DiscoverItemDto(
    val provider: String,
    @SerialName("provider_id") val providerId: String,
    @SerialName("media_type") val mediaType: String,
    val title: String,
    val year: Int? = null,
    val description: String? = null,
    @SerialName("poster_url") val posterUrl: String? = null,
    @SerialName("backdrop_url") val backdropUrl: String? = null,
    val genres: List<String> = emptyList(),
    val rating: Double? = null,
    @SerialName("rating_count") val ratingCount: Int? = null,
    val authors: List<String> = emptyList(),
    val identifiers: kotlinx.serialization.json.JsonObject? = null,
    @SerialName("library_id") val libraryId: String? = null,
    @SerialName("want_status") val wantStatus: String? = null,
    @SerialName("is_in_library") val isInLibrary: Boolean? = null,
)

@Serializable
data class DiscoverResponseDto(
    @SerialName("watch_next") val watchNext: List<InProgressItem> = emptyList(),
    @SerialName("recently_added") val recentlyAdded: List<RecentItem> = emptyList(),
    // Each entry is a discover item with the row's own fields alongside it, so it is decoded
    // twice rather than repeating DiscoverItemDto's fields.
    @SerialName("stremio_continue") val stremioContinue: List<kotlinx.serialization.json.JsonObject> = emptyList(),
    @SerialName("for_you") val forYou: List<kotlinx.serialization.json.JsonObject> = emptyList(),
    val sections: List<DiscoverSectionDto> = emptyList(),
)

@Serializable
internal data class WatchNextResponseDto(@SerialName("watch_next") val watchNext: List<InProgressItem> = emptyList())

@Serializable
data class StremioContinueExtrasDto(
    val subtitle: String? = null,
    val season: Int? = null,
    @SerialName("progress_percent") val progressPercent: Double? = null,
)

@Serializable
data class ForYouExtrasDto(
    val because: List<String> = emptyList(),
    @SerialName("because_items") val becauseItems: List<ForYouSeedDto> = emptyList(),
)

@Serializable
data class WatchHistoryItemDto(
    @SerialName("media_type") val mediaType: String,
    @SerialName("tmdb_id") val tmdbId: Long? = null,
    val title: String = "",
    val year: Int? = null,
    @SerialName("poster_url") val posterUrl: String? = null,
    @SerialName("last_watched_at") val lastWatchedAt: String? = null,
    val sources: List<String> = emptyList(),
    val excluded: Boolean = false,
)

@Serializable
data class WatchHistoryResponseDto(val items: List<WatchHistoryItemDto> = emptyList(), val total: Int = 0)

@Serializable
data class ForYouSeedDto(
    @SerialName("media_type") val mediaType: String,
    @SerialName("tmdb_id") val tmdbId: Long? = null,
    val title: String = "",
)

@Serializable
data class DiscoverSectionDto(
    val title: String,
    @SerialName("section_key") val sectionKey: String,
    @SerialName("sort_order") val sortOrder: Int,
    val items: List<DiscoverItemDto> = emptyList(),
)

@Serializable
data class DiscoverDetailDto(
    @SerialName("media_type") val mediaType: String,
    val provider: String,
    val details: kotlinx.serialization.json.JsonObject,
    @SerialName("library_id") val libraryId: String? = null,
    @SerialName("want_status") val wantStatus: String? = null,
    @SerialName("is_in_library") val isInLibrary: Boolean? = null,
)

@Serializable
data class CreateEntityResponse(val id: String)

// -- Alphabet DTOs --

@Serializable
data class AvailableLettersResponse(val letters: List<String>)

@Serializable
data class FirstByLetterResponse(val id: String, val title: String)

// -- External search DTOs --

// The video search endpoints emit `external_id` and carry no `media_type` — they
// are provider results, not discover rows, so they cannot reuse DiscoverItemDto.
@Serializable
data class ExternalVideoResultDto(
    val provider: String = "tmdb",
    @SerialName("external_id") val externalId: String = "",
    val title: String = "",
    val year: Int? = null,
    val description: String? = null,
    @SerialName("poster_url") val posterUrl: String? = null,
    val genres: List<String> = emptyList(),
    val rating: Double? = null,
    @SerialName("rating_count") val ratingCount: Int? = null,
    @SerialName("library_id") val libraryId: String? = null,
    @SerialName("want_status") val wantStatus: String? = null,
    @SerialName("is_in_library") val isInLibrary: Boolean? = null,
)

@Serializable
data class ExternalMovieSearchResponse(val results: List<ExternalVideoResultDto> = emptyList())

@Serializable
data class ExternalTvSearchResponse(val results: List<ExternalVideoResultDto> = emptyList())

@Serializable
data class ExternalBookSearchResponse(val results: List<ExternalBookDto> = emptyList())

@Serializable
data class ProviderInfoDto(
    @SerialName("provider_type") val providerType: String,
    val enabled: Boolean = false,
    val priority: Int = Int.MAX_VALUE,
    val capabilities: List<String> = emptyList(),
)

@Serializable
data class ExternalBookDto(
    val provider: String? = null,
    val title: String? = null,
    val authors: List<String>? = null,
    val description: String? = null,
    @SerialName("cover_url") val coverUrl: String? = null,
    val series: String? = null,
    @SerialName("series_index") val seriesIndex: Double? = null,
    val subjects: List<String>? = null,
    @SerialName("external_id") val externalId: String? = null,
    val rating: Double? = null,
    @SerialName("rating_count") val ratingCount: Int? = null,
    val identifiers: kotlinx.serialization.json.JsonObject? = null,
)

// -- Mapping to UI models --

internal fun formatRuntime(minutes: Int?): String? {
    if (minutes == null) return null
    val h = minutes / 60
    val m = minutes % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

/** Resolve a potentially relative image URL against the API base. */
private fun resolveUrl(base: String, url: String?): String {
    if (url.isNullOrEmpty()) return ""
    if (url.startsWith("http://") || url.startsWith("https://")) return url
    return "$base$url"
}

internal fun resolveUrlOrNull(base: String, url: String?): String? {
    if (url.isNullOrEmpty()) return null
    if (url.startsWith("http://") || url.startsWith("https://")) return url
    return "$base$url"
}

internal fun MovieDetailDto.toMovie(base: String): Movie = Movie(
    id = id,
    title = title,
    year = year ?: 0,
    posterUrl = resolveUrl(base, posterUrl),
    backdropUrl = resolveUrlOrNull(base, backdropUrl),
    logoUrl = resolveUrlOrNull(base, logoUrl),
    rating = rating?.toFloat(),
    genres = genres ?: emptyList(),
    synopsis = description,
    tagline = tagline?.takeIf { it.isNotBlank() },
    contentRating = contentRating?.takeIf { it.isNotBlank() },
    kidsOverride = kidsOverride,
    kidsAllowed = kidsAllowed,
    duration = formatRuntime(runtime),
    runtimeMinutes = runtime,
    cast = cast.map { c -> CastMember(name = c.personName, character = c.characterName, imageUrl = resolveUrlOrNull(base, c.personImageUrl), personId = c.personId) },
    directors = crew.directors(),
    writers = crew.writers(),
    filePath = if (isInLibrary) "stream" else null,
    wantStatus = wantStatus,
    isWatched = watched ?: false,
    watchPositionSeconds = watchPositionSeconds,
    inWatchlist = inWatchlist,
    releaseDate = parseIsoDate(releaseDate),
    releaseDates = releaseDates?.toModel(),
)

private fun List<CrewDto>.creditsFor(vararg jobs: String): List<String> =
    filter { it.job in jobs }.map { it.personName }.distinct()

private fun List<CrewDto>.directors() = creditsFor("Director")

private fun List<CrewDto>.writers() = creditsFor("Screenplay", "Writer", "Story")

internal fun ShowDetailDto.toTvShow(base: String): TvShow {
    val seasons = this.seasons
        .filterNot { it.isSpecial }
        .sortedBy { it.seasonSortOrder }
        .map { s ->
            Season(
                id = s.id,
                number = s.seasonSortOrder,
                episodes = s.episodes.sortedBy { it.episodeSortOrder }.map { e ->
                    Episode(
                        id = e.id,
                        number = e.episodeSortOrder,
                        title = e.title ?: "Episode ${e.episodeSortOrder}",
                        synopsis = e.description,
                        thumbnailUrl = resolveUrlOrNull(base, e.stillUrl),
                        duration = formatRuntime(e.runtime),
                        runtimeMinutes = e.runtime,
                        filePath = if (e.isInLibrary) "stream" else null,
                        isWatched = e.watched ?: false,
                        wantStatus = e.wantStatus,
                        airDate = parseIsoDate(e.airDate),
                        watchProgress = if (e.watchPositionSeconds != null && e.runtime != null && e.runtime > 0)
                            (e.watchPositionSeconds / (e.runtime * 60f)).coerceIn(0f, 1f)
                        else null,
                    )
                },
            )
        }
    return TvShow(
        id = id,
        title = title,
        year = year ?: 0,
        posterUrl = resolveUrl(base, posterUrl),
        backdropUrl = resolveUrlOrNull(base, backdropUrl),
        logoUrl = resolveUrlOrNull(base, logoUrl),
        network = network,
        contentRating = contentRating?.takeIf { it.isNotBlank() },
        kidsOverride = kidsOverride,
        kidsAllowed = kidsAllowed,
        rating = rating?.toFloat(),
        genres = genres.orEmpty(),
        synopsis = description,
        cast = cast.map { c -> CastMember(name = c.personName, character = c.characterName, imageUrl = resolveUrlOrNull(base, c.personImageUrl), personId = c.personId) },
        seasons = seasons,
        // The server's indices count the specials season this mapping drops, so they
        // are re-derived from the id against the seasons the client actually shows.
        continueEpisode = continueEpisode?.let { c ->
            seasons.indexOfEpisode(c.episodeId)?.let { (si, ei) ->
                ContinueEpisode(seasonIdx = si, episodeIdx = ei, episodeId = c.episodeId, positionSeconds = c.positionSeconds)
            }
        },
        isMonitored = isMonitored,
        autoWantMode = autoWantMode,
        inWatchlist = inWatchlist,
        nextAiring = nextAiring,
        isSport = contentCategory == "sport",
    )
}

private fun RecentMovie.toMovie(base: String): Movie = Movie(
    id = id,
    title = title,
    year = year ?: 0,
    posterUrl = resolveUrl(base, posterUrl),
    backdropUrl = resolveUrlOrNull(base, backdropUrl),
)

private fun RecentTvEntry.toTvShow(base: String): TvShow = TvShow(
    id = showId,
    title = showTitle,
    year = 0,
    posterUrl = resolveUrl(base, showPosterUrl),
    backdropUrl = resolveUrlOrNull(base, showBackdropUrl),
    network = formatEpisodeLabel(episodeIdentifier, episodeTitle).ifEmpty { null },
)

private fun RecentBook.toBook(base: String): Book = Book(
    id = id,
    title = title,
    author = authors,
    coverUrl = resolveUrl(base, coverUrl),
)

private fun BookDetailDto.toBook(base: String): Book = Book(
    id = id,
    title = title,
    author = authors.joinToString(", ") { it.name },
    coverUrl = resolveUrl(base, coverUrl),
    authors = authors.map { BookAuthor(it.id, it.name) },
    seriesId = seriesId,
    seriesName = seriesName,
    seriesIndex = seriesIndex,
    rating = rating?.toFloat(),
    description = description,
    hasAudiobook = hasAudiobook,
    formats = formats,
    narrator = narrator,
    durationSeconds = durationSeconds,
    genres = genres,
    isInLibrary = isInLibrary ?: true,
    wantStatus = wantStatus,
    seriesMemberships = seriesMemberships.map { BookSeriesEntry(seriesId = it.seriesId, seriesName = it.seriesName, seriesIndex = it.seriesIndex) },
)

private fun AuthorSummaryDto.toAuthor(base: String): Author = Author(
    id = id,
    name = name,
    bookCount = bookCount,
    seriesCount = seriesCount,
    coverUrls = coverUrls.map { resolveUrl(base, it) },
    imageUrl = resolveUrlOrNull(base, imageUrl),
    bio = bio,
    topSeriesNames = topSeriesNames,
)

private fun SeriesSummaryDto.toSeries(base: String): Series = Series(
    id = id,
    name = name,
    bookCount = bookCount,
    coverUrls = coverUrls.map { resolveUrl(base, it) },
    imageUrl = resolveUrlOrNull(base, imageUrl),
    description = description,
    authorNames = authors.joinToString(", ") { it.name },
)

private fun LibraryBookDto.toBook(base: String): Book = Book(
    id = id,
    title = title,
    author = authors.joinToString(", ") { it.name },
    coverUrl = resolveUrl(base, coverUrl),
    authors = authors.map { BookAuthor(it.id, it.name) },
    seriesId = seriesId,
    seriesName = seriesName,
    seriesIndex = seriesIndex,
    hasAudiobook = hasAudiobook,
    formats = formats,
    rating = rating?.toFloat(),
)

private fun ExternalVideoResultDto.toDiscoverItem(base: String, mediaType: String): DiscoverItem = DiscoverItem(
    provider = provider,
    providerId = externalId,
    mediaType = mediaType,
    title = title,
    year = year,
    description = description,
    posterUrl = resolveUrl(base, posterUrl),
    backdropUrl = null,
    genres = genres,
    rating = rating?.toFloat(),
    ratingCount = ratingCount,
    authors = emptyList(),
    libraryId = libraryId,
    wantStatus = wantStatus,
    isInLibrary = isInLibrary ?: false,
)

private fun DiscoverItemDto.toDiscoverItem(base: String): DiscoverItem = DiscoverItem(
    provider = provider,
    providerId = providerId,
    mediaType = mediaType,
    title = title,
    year = year,
    description = description,
    posterUrl = resolveUrl(base, posterUrl),
    backdropUrl = resolveUrlOrNull(base, backdropUrl),
    genres = genres,
    rating = rating?.toFloat(),
    ratingCount = ratingCount,
    authors = authors,
    libraryId = libraryId,
    wantStatus = wantStatus,
    isInLibrary = isInLibrary ?: false,
)

private fun kotlinx.serialization.json.JsonObject.toStremioContinueItem(base: String): StremioContinueItem {
    val extras = json.decodeFromJsonElement(StremioContinueExtrasDto.serializer(), this)
    return StremioContinueItem(
        item = json.decodeFromJsonElement(DiscoverItemDto.serializer(), this).toDiscoverItem(base),
        subtitle = extras.subtitle,
        season = extras.season,
        progress = extras.progressPercent?.div(100.0)?.toFloat(),
    )
}

private fun kotlinx.serialization.json.JsonObject.toForYouItem(base: String): ForYouItem {
    val extras = json.decodeFromJsonElement(ForYouExtrasDto.serializer(), this)
    return ForYouItem(
        item = json.decodeFromJsonElement(DiscoverItemDto.serializer(), this).toDiscoverItem(base),
        because = extras.because,
        seeds = extras.becauseItems.mapNotNull { seed -> seed.tmdbId?.let { ForYouSeed(seed.mediaType, it, seed.title) } },
    )
}

private fun InProgressItem.toContinueItem(base: String): ContinueItem = ContinueItem(
    id = mediaId,
    mediaType = when (mediaType) {
        "episode" -> MediaType.TvShow
        "event" -> MediaType.SportsEvent
        "book" -> MediaType.Book
        "audiobook" -> MediaType.Audiobook
        else -> MediaType.Movie
    },
    title = title,
    subtitle = subtitle,
    imageUrl = resolveUrl(base, imageUrl),
    backdropUrl = resolveUrlOrNull(base, backdropUrl),
    progress = (progressPercent?.div(100.0))?.toFloat() ?: 0f,
    link = link,
    started = (positionSeconds ?: 0) > 0 || (progressPercent ?: 0.0) > 0.0,
    remainingSeconds = durationSeconds?.let { (it - (positionSeconds ?: 0)).toInt() }?.takeIf { it > 0 },
)

private fun RecentItem.toRecentlyAddedItem(base: String): RecentlyAddedItem = RecentlyAddedItem(
    itemType = itemType,
    id = id,
    title = title,
    subtitle = subtitle,
    imageUrl = resolveUrl(base, imageUrl),
    backdropUrl = resolveUrlOrNull(base, backdropUrl),
    link = link,
)

// -- API client --

/**
 * The synthesis endpoint reports refusals as `{"error": "..."}`, and that text names the
 * actual limit hit. Ktor's reason phrase alone ("Bad Request") is not diagnosable.
 */
internal fun ttsErrorMessage(statusCode: Int, reason: String, body: String): String =
    parseServerError(body).message ?: "HTTP $statusCode $reason"

class KobblerApi(
    internal val httpClient: HttpClient,
    val apiUrl: String,
    private val token: String,
    /** True if the user can perform content management actions (want, monitor). */
    val canWant: Boolean = true,
    /** Admin only: whether a title is shown to Kids profiles regardless of its rating. */
    val canManageKids: Boolean = false,
    private val originProfiles: OriginProfiles = OriginProfiles.Persistent,
    private val decodesInterlaced: () -> Boolean = ::deviceDecodesInterlaced,
    private val hdrFormats: () -> List<String> = ::deviceHdrFormats,
    internal val offline: OfflineHooks? = null,
) {
    /** True if kobload/debrid streaming is configured on the server. Set during initial load. */
    var canStream: Boolean = false
    var castReceiverAppId: String? = null
    private val streamingProbeMutex = Mutex()
    private var streamingProbed = false
    val authHeaders: Map<String, String> = mapOf("Authorization" to "Bearer $token")

    /**
     * What the video player has to send to fetch [url].
     *
     * The session-keyed routes — direct, both playlists, init, segments and the live
     * buffer — are served unauthenticated, keyed on the session id the client already
     * had to be authorised to mint. Handing libVLC an Authorization header for them buys
     * nothing and costs everything: it cannot send one, so every byte is relayed through
     * the in-app localhost proxy instead of coming straight off the socket.
     */
    private val playbackTokenLock = Mutex()
    private var playbackToken: String? = null
    private var playbackTokenMintedAt: Instant? = null

    /**
     * The URL to hand a player for [url].
     *
     * Never stored as the stream URL for diagnostics: a token in a failure report is a
     * credential in a log. See [dev.kobbler.app.ui.player.GlobalPlayerState.lastStreamUrl].
     */
    suspend fun playerUrl(url: String): String {
        if (!playerUrlNeedsToken(url) || offline?.servesLocally(url) == true) return url
        val token = mintedPlaybackToken() ?: return url
        return withPlaybackToken(url, token)
    }

    /**
     * The credential to hand a cast receiver.
     *
     * A receiver holds no login and calls the API itself — it saves the position, follows
     * skip segments and fetches the next episode — so it needs the same short-lived token
     * a player URL carries. The same one: it is already minted for this watch, and a
     * second would expire on its own schedule for no gain.
     */
    suspend fun castToken(): String? = mintedPlaybackToken()

    /**
     * Mint the playback credential ahead of the first source that will need one.
     *
     * Opening a file is on the path between a tap and sound, and a screen plays many of
     * them, so the round trip is paid once when the screen opens rather than in front of
     * every one — which is also what keeps [audioSource] synchronous.
     */
    suspend fun warmPlaybackToken() {
        mintedPlaybackToken()
    }

    /**
     * Attach the credential already in hand, if this URL needs one.
     *
     * Reads the token without the lock on purpose: it only ever goes from absent to
     * present to replaced, so the worst a racing reader can get is the previous one, and
     * the callers that cannot afford to suspend are the ones that need it.
     */
    private fun withHeldPlaybackToken(url: String): String {
        if (!playerUrlNeedsToken(url)) return url
        val token = playbackToken ?: return url
        return withPlaybackToken(url, token)
    }

    /**
     * Forget the current token so the next URL mints a fresh one.
     *
     * A token the server no longer knows — it restarted, or the cleanup job swept an
     * expired row — fails as a 401 on a socket libVLC is already reading from, which
     * reaches nothing. So a playback failure is a reason to stop trusting it.
     */
    suspend fun invalidatePlaybackToken() {
        playbackTokenLock.withLock {
            playbackToken = null
            playbackTokenMintedAt = null
        }
    }

    private suspend fun mintedPlaybackToken(): String? = playbackTokenLock.withLock {
        val held = playbackToken
        val mintedAt = playbackTokenMintedAt
        if (held != null && mintedAt != null && Clock.System.now() - mintedAt < PlaybackTokenReuse) {
            return@withLock held
        }
        val minted = quiet(null) {
            decode<PlaybackTokenDto>(
                post("/api/v1/admin/playback/tokens", """{"ttl_hours":$PlaybackTokenTtlHours}"""),
            ).token
        }
        if (minted != null) {
            playbackToken = minted
            playbackTokenMintedAt = Clock.System.now()
        }
        minted
    }

    internal val fileClient: HttpClient by lazy { SharedHttp.fileClient() }
    internal val eventClient: HttpClient by lazy { SharedHttp.eventClient() }

    /**
     * How close the server actually is, fed by every request that goes to it. See
     * [OriginLatency] — the buffer a player prebuffers is sized against this rather than
     * against what the hostname looks like.
     */
    private val originLatency = OriginLatency()

    /** Buffer depth for a player source. See [networkCacheMsFor]. */
    fun networkCacheMsFor(url: String, bufferClass: BufferClass = BufferClass.Vod): Int =
        networkCacheMsFor(url, apiUrl, originLatency.roundTripMs(), originProfiles.get(profileKeyFor(url, bufferClass)), bufferClass)

    /**
     * Time the network to the server rather than a request to it: on a Chromecast an HTTP
     * call costs tens of ms of the box's own time over a Wi-Fi round trip of a few, and
     * sizing the stream's buffer by that read the server as remote. A TCP handshake where
     * sockets are available, best of two; `/health`, which does no work, elsewhere.
     */
    suspend fun probeOrigin() {
        val host = hostOf(apiUrl) ?: return
        val roundTrip = listOfNotNull(
            connectRoundTripMs(host, portOf(apiUrl)),
            connectRoundTripMs(host, portOf(apiUrl)),
        ).minOrNull() ?: runCatching {
            val start = kotlin.time.TimeSource.Monotonic.markNow()
            if (!httpClient.get("$apiUrl/health").status.isSuccess()) return
            start.elapsedNow().inWholeMilliseconds
        }.getOrNull() ?: return
        originLatency.record(roundTrip)
        val measured = originLatency.roundTripMs() ?: return
        originProfiles.update(host) { it.copy(roundTripMs = measured) }
    }

    /** The stream ran dry mid-play: give its profile a deeper buffer from the next start. */
    fun recordStall(streamUrl: String, bufferClass: BufferClass) =
        originProfiles.update(profileKeyFor(streamUrl, bufferClass)) { it.afterStall() }

    /** A long stretch played without a stall: ease its profile's buffer back a step. */
    fun recordCleanSession(streamUrl: String, bufferClass: BufferClass) =
        originProfiles.update(profileKeyFor(streamUrl, bufferClass)) { it.afterCleanSession() }

    /** Measured round trip to the server, or null before anything has been timed. */
    fun originRoundTripMs(): Long? = originLatency.roundTripMs()

    private suspend fun <T> timed(block: suspend () -> T): T {
        val start = kotlin.time.TimeSource.Monotonic.markNow()
        val result = block()
        // Only a completed request times the round trip. A failure can return in
        // microseconds without having reached the network at all.
        originLatency.record(start.elapsedNow().inWholeMilliseconds)
        return result
    }

    private suspend fun get(path: String): String {
        val offline = offline ?: return timed { httpClient.get("$apiUrl$path") { header(HttpHeaders.Authorization, "Bearer $token") }.checkedBody() }
        // Waiting out a connect timeout to learn what the last failure already said is the
        // whole cost of opening a downloaded title with no network.
        if (offline.isOffline) offline.cachedResponse(path)?.let { return it }
        val body = overNetwork(offline) {
            timed { httpClient.get("$apiUrl$path") { header(HttpHeaders.Authorization, "Bearer $token") }.checkedBody() }
        } ?: return offline.cachedResponse(path) ?: throw OfflineException()
        offline.onResponse(path, body)
        return body
    }

    /** Null when the request never reached the server; a server that answered with an error still throws. */
    private suspend fun <T> overNetwork(offline: OfflineHooks, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        throw e
    } catch (e: Exception) {
        offline.onNetworkFailure()
        null
    }

    private suspend fun post(path: String, body: String): String = write("POST", path, body) {
        timed {
            httpClient.post("$apiUrl$path") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(body)
            }.checkedBody()
        }
    }

    private suspend fun put(path: String, body: String): String = write("PUT", path, body) {
        httpClient.put("$apiUrl$path") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }.checkedBody()
    }

    private suspend fun patch(path: String, body: String): String = write("PATCH", path, body) {
        httpClient.patch("$apiUrl$path") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }.checkedBody()
    }

    /**
     * A progress write made with no network is kept and sent on reconnect rather than lost:
     * watching a download on a plane is exactly when it cannot reach the server.
     */
    private suspend fun write(method: String, path: String, body: String, send: suspend () -> String): String {
        val offline = offline ?: return send()
        if (offline.isOffline && offline.queueWrite(offlineOwner, method, path, body)) {
            offline.onWrite(path, body)
            return ""
        }
        val result = try {
            send()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            offline.onNetworkFailure()
            if (!offline.queueWrite(offlineOwner, method, path, body)) throw e
            ""
        }
        offline.onWrite(path, body)
        return result
    }

    /** Whose progress a kept write is: sent later, it must go to the same server as the same user. */
    internal val offlineOwner: String by lazy { dev.kobbler.app.offline.stableHash("$apiUrl|$token") }

    /** Sends a write [OfflineHooks.queueWrite] kept, bypassing the queue it came from. */
    internal suspend fun replayWrite(method: String, path: String, body: String) {
        val resp = httpClient.request("$apiUrl$path") {
            this.method = HttpMethod.parse(method)
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        resp.checkedBody()
    }

    internal suspend fun isReachable(): Boolean =
        httpClient.get("$apiUrl/health").status.isSuccess()

    private suspend fun delete(path: String): String {
        val resp = httpClient.delete("$apiUrl$path") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        return resp.checkedBody()
    }

    private suspend fun HttpResponse.checkedBody(): String {
        if (!status.isSuccess()) {
            val body = try {
                bodyAsText()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ""
            }
            val error = parseServerError(body)
            throw ApiException(status.value, status.description, error.message, error.reason)
        }
        // bodyAsText() suspends on network I/O. Dispatching it to Dispatchers.Default pins a
        // CPU worker for the whole read, so concurrent requests queue behind core count and
        // requestTimeoutMillis counts that wait. Parsing is the part that needs the offload,
        // and decode()/decodeMap() already do it.
        return bodyAsText()
    }

    private suspend inline fun <reified T> decode(body: String): T =
        withContext(Dispatchers.Default) { json.decodeFromString<T>(body) }

    private suspend inline fun <reified T, R> decodeMap(body: String, crossinline map: (T) -> R): R =
        withContext(Dispatchers.Default) { map(json.decodeFromString<T>(body)) }

    private inline fun <T> quiet(fallback: T, block: () -> T): T =
        try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { fallback }

    suspend fun getDashboard(): DashboardData = coroutineScope {
        val probe = async { quiet(Unit) { checkStreamingAvailability() } }
        val body = get("/api/v1/admin/dashboard")
        probe.await()
        decodeMap<DashboardResponse, DashboardData>(body) { resp ->
            DashboardData(
                readNextItems = resp.readNext.map { it.toContinueItem(apiUrl) },
                movies = resp.recentMovies.map { it.toMovie(apiUrl) },
                shows = resp.recentTv.map { it.toTvShow(apiUrl) },
                books = resp.recentBooks.map { it.toBook(apiUrl) },
                ebooks = resp.recentEbooks.map { it.toBook(apiUrl) },
                audiobooks = resp.recentAudiobooks.map { it.toBook(apiUrl) },
                sports = resp.recentSports.map { it.toTvShow(apiUrl) },
            )
        }
    }

    suspend fun getMovie(id: String): Movie =
        decodeMap<MovieDetailDto, Movie>(get("/api/v1/admin/movies/$id")) { it.toMovie(apiUrl) }

    suspend fun getShow(id: String): TvShow =
        decodeMap<ShowDetailDto, TvShow>(get("/api/v1/admin/tv/shows/$id")) { it.toTvShow(apiUrl) }

    /** Resolve an episode ID to its show ID (episode → season → show). */
    suspend fun getShowIdForEpisode(episodeId: String): String {
        val seasonId = decode<EpisodeDto>(get("/api/v1/admin/tv/episodes/$episodeId")).seasonId
            ?: throw Exception("Episode $episodeId has no season_id")
        return decode<SeasonDto>(get("/api/v1/admin/tv/seasons/$seasonId")).showId
            ?: throw Exception("Season $seasonId has no show_id")
    }


    private fun invalidateMovies() {
        DataCache.invalidate("home", "dashboard", "movies", "wanted")
        DataCache.invalidatePrefix("movie-")
        DataCache.invalidatePrefix("movies:")
    }

    // A Discover page held in the back stack for this title would otherwise come back
    // from cache still offering to stream something that is now in the library.
    private fun invalidateAutoCreated() {
        DataCache.invalidatePrefix("discover-")
        invalidateMovies()
        invalidateTvShows()
    }

    private fun invalidateTvShows() {
        DataCache.invalidate("home", "dashboard", "tvshows", "sports", "wanted")
        DataCache.invalidatePrefix("show-")
        DataCache.invalidatePrefix("tvshows:")
    }

    private fun invalidateBooks() {
        DataCache.invalidate("home", "dashboard", "books_all", "books_audiobooks", "wanted", "authors", "series")
    }

    // After the write, not before it: a detail page that refetched on return from the
    // reader would otherwise read the position the exit save had not delivered yet.
    private fun invalidateBookProgress(bookId: String) {
        DataCache.invalidate("home", "dashboard", DataCache.bookProgressKey(bookId))
    }

    /** [allowed] null hands the decision back to the title's rating. */
    suspend fun setMovieKidsOverride(movieId: String, allowed: Boolean?) {
        patch("/api/v1/admin/movies/$movieId", "{\"kids_override\":$allowed}")
        invalidateMovies()
    }

    suspend fun setShowKidsOverride(showId: String, allowed: Boolean?) {
        patch("/api/v1/admin/tv/shows/$showId", "{\"kids_override\":$allowed}")
        invalidateTvShows()
    }

    /** Want a movie (create if needed via discover, or want existing). */
    suspend fun wantMovieById(movieId: String) {
        post("/api/v1/admin/movies/$movieId/want", "{}")
        invalidateMovies()
    }

    /**
     * Set how much of a show to want. [autoWantMode] is one of the server's
     * `none` / `new_only` / `all` / `fill_gaps`; anything but `none` is applied to every
     * existing season straight away, not just to future episodes.
     */
    suspend fun monitorShow(showId: String, autoWantMode: String = "new_only", monitored: Boolean = true) {
        post(
            "/api/v1/admin/tv/shows/$showId/monitor",
            """{"is_monitored":$monitored,"auto_want_mode":"$autoWantMode"}""",
        )
        invalidateTvShows()
    }

    /** Want every episode of a show that has no file, without changing monitoring. */
    suspend fun wantAllMissingEpisodes(showId: String) {
        post("/api/v1/admin/tv/shows/$showId/want-all-missing", "{}")
        invalidateTvShows()
    }

    /** Add a show or movie to the caller's own "want to watch" list. */
    suspend fun addToWatchlist(mediaType: String, mediaId: String) {
        post("/api/v1/admin/watchlist", """{"media_type":"$mediaType","media_id":"$mediaId"}""")
        if (mediaType == "show") invalidateTvShows() else invalidateMovies()
    }

    suspend fun removeFromWatchlist(mediaType: String, mediaId: String) {
        delete("/api/v1/admin/watchlist/$mediaType/$mediaId")
        if (mediaType == "show") invalidateTvShows() else invalidateMovies()
    }

    /** Want a specific episode. */
    suspend fun wantEpisode(episodeId: String) {
        post("/api/v1/admin/tv/episodes/$episodeId/want", "{}")
        invalidateTvShows()
    }

    /** Want all episodes in a season. */
    suspend fun wantSeason(seasonId: String) {
        post("/api/v1/admin/tv/seasons/$seasonId/want", "{}")
        invalidateTvShows()
    }

    /** Want a book. */
    suspend fun wantBookById(bookId: String) {
        post("/api/v1/library/books/$bookId/want", "{}")
        invalidateBooks()
    }

    /** Whether the server now counts the movie watched; null when it did not say, or the save was queued offline. */
    suspend fun reportMovieProgress(movieId: String, positionSeconds: Int): Boolean? =
        savedVerdict(patch("/api/v1/admin/movies/$movieId/watch-state/position", positionBody(positionSeconds)))

    /** Whether the server now counts the episode watched; null when it did not say, or the save was queued offline. */
    suspend fun reportEpisodeProgress(episodeId: String, positionSeconds: Int): Boolean? =
        savedVerdict(patch("/api/v1/admin/tv/episodes/$episodeId/watch-state/position", positionBody(positionSeconds)))

    private suspend fun savedVerdict(body: String): Boolean? =
        body.takeIf { it.isNotBlank() }?.let { quiet(null) { decode<PositionSaved>(it).watched } }

    /**
     * A position save names the device it came from, so the server can offer it to this
     * user's other open players. See [PlaybackClient].
     */
    private fun positionBody(positionSeconds: Int): String = buildJsonObject {
        put("position_seconds", positionSeconds)
        put("client_id", PlaybackClient.id)
        put("client_label", deviceLabel)
    }.toString()

    /**
     * The newest position any of this user's devices saved for the show (or movie)
     * [mediaId] belongs to, and the scope id `watch_progress` events carry for it.
     */
    suspend fun getPlaybackHandoff(mediaType: String, mediaId: String): PlaybackHandoffResponse? =
        quiet<PlaybackHandoffResponse?>(null) {
            decode<PlaybackHandoffResponse>(get("/api/v1/admin/playback/handoff?media_type=$mediaType&media_id=$mediaId"))
        }

    suspend fun setMovieWatched(movieId: String, watched: Boolean) {
        put("/api/v1/admin/movies/$movieId/watch-state", watchStateBody(watched))
        DataCache.invalidateWatchProgress()
        DataCache.invalidate("movies")
    }

    /** Set the signed-in user's display name; blank clears it. */
    suspend fun setDisplayName(name: String) {
        val trimmed = name.trim()
        val value = if (trimmed.isEmpty()) "null" else json.encodeToString(String.serializer(), trimmed)
        put("/api/v1/admin/me/display-name", "{\"display_name\":$value}")
    }

    suspend fun setEpisodeWatched(episodeId: String, watched: Boolean) {
        put("/api/v1/admin/tv/episodes/$episodeId/watch-state", watchStateBody(watched))
        DataCache.invalidateWatchProgress()
        DataCache.invalidate("tvshows", "sports")
    }

    /** Every episode of the show before this one, specials aside. */
    suspend fun markEarlierEpisodesWatched(episodeId: String) {
        put("/api/v1/admin/tv/episodes/$episodeId/watch-state/earlier", """{"watched":true}""")
        DataCache.invalidateWatchProgress()
        invalidateTvShows()
    }

    suspend fun setShowWatched(showId: String, watched: Boolean) {
        put("/api/v1/admin/tv/shows/$showId/watch-state", """{"watched":$watched}""")
        DataCache.invalidateWatchProgress()
        invalidateTvShows()
    }

    // Either way the saved position goes: the server keeps it unless told otherwise, so
    // an unwatched title would still offer to resume from wherever it was last left.
    private fun watchStateBody(watched: Boolean) = """{"watched":$watched,"position_seconds":0}"""

    // Null when the server could not say, so a caller can fall back to what it already has.
    suspend fun getMovieProgress(movieId: String): Int? = progress("/api/v1/admin/movies/$movieId/watch-state")

    suspend fun getEpisodeProgress(episodeId: String): Int? = progress("/api/v1/admin/tv/episodes/$episodeId/watch-state")

    private suspend fun progress(watchStatePath: String): Int? =
        offline?.pendingResumeSeconds(offlineOwner, watchStatePath)
            ?: quiet(null) { decode<PositionResponse>(get(watchStatePath)).resumeSeconds }

    /** Null when the server could not be asked. */
    suspend fun isWatched(mediaType: String, mediaId: String): Boolean? = getWatchState(mediaType, mediaId)?.watched

    suspend fun getWatchState(mediaType: String, mediaId: String): PositionResponse? = quiet(null) {
        val path = if (mediaType == "movie") "/api/v1/admin/movies/$mediaId/watch-state" else "/api/v1/admin/tv/episodes/$mediaId/watch-state"
        decode<PositionResponse>(get(path))
    }

    // -- Books --

    suspend fun getBooks(
        cursor: String? = null,
        direction: String? = null,
        around: String? = null,
        aroundBefore: Int? = null,
        format: String? = null,
    ): PaginatedBooks {
        val path = buildString {
            append("/api/v1/library/books?in_library=true")
            if (cursor != null) append("&cursor=$cursor")
            if (direction != null) append("&direction=$direction")
            if (around != null) append("&around=$around")
            if (aroundBefore != null) append("&around_before=$aroundBefore")
            if (format != null) append("&format=$format")
        }
        return decode<PaginatedBooks>(get(path))
    }

    fun bookListToModels(items: List<BookListDto>): List<Book> = items.map {
        Book(
            id = it.id,
            title = it.title,
            author = it.authors.joinToString(", ") { a -> a.name },
            coverUrl = resolveUrl(apiUrl, it.coverUrl),
            hasAudiobook = it.hasAudiobook,
            formats = it.formats,
        )
    }

    suspend fun getMoviesList(
        cursor: String? = null,
        direction: String? = null,
        around: String? = null,
        aroundBefore: Int? = null,
        genre: String? = null,
    ): PaginatedMovies {
        val path = buildString {
            append("/api/v1/admin/movies?is_in_library=true")
            if (genre != null) append("&genre=${genre.encodeURLParameter()}")
            if (cursor != null) append("&cursor=$cursor")
            if (direction != null) append("&direction=$direction")
            if (around != null) append("&around=$around")
            if (aroundBefore != null) append("&around_before=$aroundBefore")
        }
        return decode<PaginatedMovies>(get(path))
    }

    fun movieListToModels(items: List<MovieListDto>): List<Movie> = items.map {
        Movie(
            id = it.id, title = it.title, year = it.year ?: 0,
            posterUrl = resolveUrl(apiUrl, it.posterUrl), backdropUrl = resolveUrlOrNull(apiUrl, it.backdropUrl),
        )
    }

    suspend fun getShowsList(
        cursor: String? = null,
        direction: String? = null,
        around: String? = null,
        aroundBefore: Int? = null,
        status: String? = null,
        genre: String? = null,
    ): PaginatedShows {
        val path = buildString {
            append("/api/v1/admin/tv/shows")
            val sep = "?"
            var first = true
            fun param(key: String, value: String) { append(if (first) { first = false; sep } else "&"); append("$key=$value") }
            if (status != null) param("status", status)
            if (genre != null) param("genre", genre.encodeURLParameter())
            if (cursor != null) param("cursor", cursor)
            if (direction != null) param("direction", direction)
            if (around != null) param("around", around)
            if (aroundBefore != null) param("around_before", aroundBefore.toString())
        }
        return decode<PaginatedShows>(get(path))
    }

    suspend fun getSportsList(
        cursor: String? = null,
        direction: String? = null,
        around: String? = null,
        aroundBefore: Int? = null,
    ): PaginatedShows {
        val path = buildString {
            append("/api/v1/admin/sports/leagues")
            val sep = "?"
            var first = true
            fun param(key: String, value: String) { append(if (first) { first = false; sep } else "&"); append("$key=$value") }
            if (cursor != null) param("cursor", cursor)
            if (direction != null) param("direction", direction)
            if (around != null) param("around", around)
            if (aroundBefore != null) param("around_before", aroundBefore.toString())
        }
        return decode<PaginatedShows>(get(path))
    }

    fun showListToModels(items: List<ShowListDto>): List<TvShow> = items.map {
        TvShow(
            id = it.id, title = it.title, year = it.year ?: 0,
            posterUrl = resolveUrl(apiUrl, it.posterUrl), backdropUrl = resolveUrlOrNull(apiUrl, it.backdropUrl),
            network = it.network,
        )
    }

    suspend fun getMovieGenres(): List<GenreCount> = genres("/api/v1/admin/movies/genres")

    suspend fun getShowGenres(): List<GenreCount> = genres("/api/v1/admin/tv/shows/genres")

    private suspend fun genres(path: String): List<GenreCount> =
        decode<GenresResponse>(get(path)).genres.map { it.copy(backdropUrl = resolveUrlOrNull(apiUrl, it.backdropUrl)) }

    // -- Alphabet navigation --

    suspend fun getMovieLetters(genre: String? = null): List<String> =
        decode<AvailableLettersResponse>(get("/api/v1/admin/movies/available-letters" + letterQuery("genre" to genre))).letters

    suspend fun getMovieFirstByLetter(letter: String, genre: String? = null): FirstByLetterResponse =
        decode<FirstByLetterResponse>(get("/api/v1/admin/movies/first-by-letter" + letterQuery("letter" to letter, "genre" to genre)))

    suspend fun getTvLetters(genre: String? = null, status: String? = null): List<String> =
        decode<AvailableLettersResponse>(get("/api/v1/admin/tv/shows/available-letters" + letterQuery("genre" to genre, "status" to status))).letters

    suspend fun getTvFirstByLetter(letter: String, genre: String? = null, status: String? = null): FirstByLetterResponse =
        decode<FirstByLetterResponse>(get("/api/v1/admin/tv/shows/first-by-letter" + letterQuery("letter" to letter, "genre" to genre, "status" to status)))

    private fun letterQuery(vararg params: Pair<String, String?>): String =
        params.filter { it.second != null }
            .joinToString("&") { (k, v) -> "$k=${v!!.encodeURLParameter()}" }
            .let { if (it.isEmpty()) "" else "?$it" }

    suspend fun getSportsLetters(): List<String> =
        decode<AvailableLettersResponse>(get("/api/v1/admin/sports/leagues/available-letters")).letters

    suspend fun getSportsFirstByLetter(letter: String): FirstByLetterResponse =
        decode<FirstByLetterResponse>(get("/api/v1/admin/sports/leagues/first-by-letter?letter=${letter.encodeURLParameter()}"))

    suspend fun getBookLetters(format: String? = null): List<String> =
        decode<AvailableLettersResponse>(
            get("/api/v1/library/books/available-letters?in_library=true" + formatParam(format))
        ).letters

    suspend fun getBookFirstByLetter(letter: String, format: String? = null): FirstByLetterResponse =
        decode<FirstByLetterResponse>(
            get("/api/v1/library/books/first-by-letter?in_library=true&letter=${letter.encodeURLParameter()}" + formatParam(format))
        )

    private fun formatParam(format: String?) = if (format != null) "&format=$format" else ""

    suspend fun getBook(id: String): Book =
        decode<BookDetailDto>(get("/api/v1/library/books/$id")).toBook(apiUrl)

    // -- Activity feed --

    suspend fun getActivity(cursor: String? = null, limit: Int? = null): PaginatedActivity {
        val path = buildString {
            append("/api/v1/library/activity")
            val params = buildList {
                if (cursor != null) add("cursor=$cursor")
                if (limit != null) add("limit=$limit")
            }
            if (params.isNotEmpty()) {
                append('?')
                append(params.joinToString("&"))
            }
        }
        return decode<PaginatedActivity>(get(path))
    }

    // -- Authors --

    suspend fun getAuthors(cursor: String? = null): PaginatedAuthors {
        val path = buildString {
            append("/api/v1/library/authors?include_no_books=false")
            if (cursor != null) append("&cursor=$cursor")
        }
        return decode<PaginatedAuthors>(get(path))
    }

    fun authorListToModels(items: List<AuthorSummaryDto>): List<Author> =
        items.map { it.toAuthor(apiUrl) }

    suspend fun getAuthor(id: String): Author =
        decode<AuthorSummaryDto>(get("/api/v1/library/authors/$id")).toAuthor(apiUrl)

    suspend fun getAuthorBooks(authorId: String): List<Book> =
        decodeMap<List<LibraryBookDto>, List<Book>>(get("/api/v1/library/authors/$authorId/books")) { dtos ->
            dtos.map { it.toBook(apiUrl) }
        }

    // -- Series --

    suspend fun getSeriesList(cursor: String? = null): PaginatedSeries {
        val path = buildString {
            append("/api/v1/library/series?include_no_books=false")
            if (cursor != null) append("&cursor=$cursor")
        }
        return decode<PaginatedSeries>(get(path))
    }

    fun seriesListToModels(items: List<SeriesSummaryDto>): List<Series> =
        items.map { it.toSeries(apiUrl) }

    suspend fun getSeries(id: String): Series =
        decode<SeriesSummaryDto>(get("/api/v1/library/series/$id")).toSeries(apiUrl)

    suspend fun getSeriesBooks(seriesId: String): List<Book> =
        decodeMap<List<LibraryBookDto>, List<Book>>(get("/api/v1/library/series/$seriesId/books")) { dtos ->
            dtos.map { it.toBook(apiUrl) }
        }

    // -- Audiobook --

    suspend fun getAudioFiles(bookId: String): List<AudioFileDto> =
        decode(get("/api/v1/admin/books/$bookId/audio-files"))

    suspend fun getUnifiedChapters(bookId: String): List<UnifiedChapterDto> =
        decode(get("/api/v1/admin/books/$bookId/chapters"))

    suspend fun getAudioPosition(bookId: String): AudioPositionDto = quiet(AudioPositionDto()) {
        val path = "/api/v1/admin/books/$bookId/audio-position"
        decode<AudioPositionDto>(offline?.pendingWriteBody(offlineOwner, path) ?: get(path))
    }

    suspend fun saveAudioPosition(bookId: String, fileId: String, positionSeconds: Double, progressPercent: Double) {
        put("/api/v1/admin/books/$bookId/audio-position", """{"file_id":"$fileId","position_seconds":$positionSeconds,"progress_percent":$progressPercent}""")
        invalidateBookProgress(bookId)
    }

    suspend fun getTranscriptSegments(bookId: String): List<TranscriptSegmentDto> =
        decode(get("/api/v1/admin/books/$bookId/transcript/segments"))

    suspend fun getReadAlongData(bookId: String): ReadAlongDataDto =
        decode(get("/api/v1/admin/books/$bookId/read-along-data"))

    /** Build the direct audio streaming URL for a book's audio file. */
    fun audioStreamUrl(bookId: String, fileId: String): String =
        "$apiUrl/api/v1/admin/books/$bookId/audio/$fileId"

    /**
     * The player source for one audiobook file: its URL, its auth, the buffer depth its
     * host warrants, and whether it is MP3. Every chapter hand-off built these by hand, which
     * is a chance per hand-off for one of them to be forgotten.
     */
    fun audioSource(bookId: String, file: AudioFileDto): dev.kobbler.kobplayer.MediaSource.Url {
        val mpegAudio = file.format.equals("mp3", ignoreCase = true)
        offline?.audioUrl(bookId, file.id)?.let {
            return dev.kobbler.kobplayer.MediaSource.Url(url = it, networkCacheMs = LanNetworkCacheMs, mpegAudio = mpegAudio)
        }
        // The server's mp4 copy of an mp3 seeks by sample table on every player; the web
        // element and libVLC otherwise land seconds off the time they report.
        val url = audioStreamUrl(bookId, file.id) + if (mpegAudio) "?container=mp4" else ""
        return dev.kobbler.kobplayer.MediaSource.Url(
            url = withHeldPlaybackToken(url),
            networkCacheMs = networkCacheMsFor(url),
            mpegAudio = mpegAudio,
        )
    }

    /** Build the book file download URL. Optionally request a specific format. */
    fun bookFileUrl(bookId: String, format: String? = null): String =
        if (format != null) "$apiUrl/api/v1/admin/books/$bookId/file?format=$format"
        else "$apiUrl/api/v1/admin/books/$bookId/file"

    // -- Reader Pack --

    suspend fun readerPackManifest(bookId: String): Manifest =
        PackJson.decodeFromString(get("/api/v1/admin/books/$bookId/reader-pack"))

    suspend fun readerPackChapter(bookId: String, chapter: Int): PackChapter =
        PackJson.decodeFromString(readerPackChapterJson(bookId, chapter))

    suspend fun readerPackChapterJson(bookId: String, chapter: Int): String =
        get("/api/v1/admin/books/$bookId/reader-pack/chapters/$chapter")

    fun readerPackAssetUrl(bookId: String, src: String): String =
        "$apiUrl/api/v1/admin/books/$bookId/reader-pack/assets/" +
            src.split('/').joinToString("/") { it.encodeURLPathPart() }

    /** An asset's bytes from the server, or from the offline store for a downloaded book. */
    suspend fun readerPackAssetBytes(bookId: String, src: String): ByteArray? {
        val url = readerPackAssetUrl(bookId, src)
        dev.kobbler.app.offline.Offline.engine?.store?.imageBytes(url)?.let { return it }
        return quiet<ByteArray?>(null) {
            fileClient.get(url) { authHeaders.forEach { (k, v) -> header(k, v) } }
                .takeIf { it.status.isSuccess() }
                ?.readRawBytes()
        }
    }

    // -- Reader Progress --

    /** Null when the state could not be read. The position is the latest across every
     *  device — app, web reader and Kobo — resolved into this book's reader pack. */
    suspend fun readerProgressOrNull(bookId: String): ReaderProgressDto? = quiet<ReaderProgressDto?>(null) {
        val path = "/api/v1/admin/books/$bookId/reader-progress"
        decode<ReaderProgressDto>(offline?.pendingWriteBody(offlineOwner, path) ?: get("$path?position=true"))
    }

    suspend fun getReaderProgress(bookId: String): ReaderProgressDto =
        readerProgressOrNull(bookId) ?: ReaderProgressDto()

    suspend fun saveReaderProgress(bookId: String, position: Position?, progressPercent: Double) {
        val body = buildJsonObject {
            position?.let {
                put("position", buildJsonObject {
                    put("chapter", it.chapter)
                    put("block", it.block)
                    put("offset", it.offset)
                })
            }
            put("progress_percent", progressPercent)
        }
        post("/api/v1/admin/books/$bookId/reader-progress", body.toString())
        invalidateBookProgress(bookId)
    }

    // -- TTS --

    suspend fun getTtsStatus(): TtsStatusDto = quiet(TtsStatusDto()) {
        decode<TtsStatusDto>(get("/api/v1/admin/tts/status"))
    }

    /**
     * Stream TTS synthesis, forwarding each decoded [TtsStreamEvent] to [onEvent].
     *
     * Framed on JSON object boundaries rather than newlines — see [JsonObjectFramer].
     * Unknown event types arrive as [TtsStreamEvent.Other] and an object that will not
     * parse is dropped, so nothing on the wire can take the read down.
     */
    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    suspend fun streamTtsSynthesize(
        text: String,
        voice: String? = null,
        speed: Double? = null,
        bookId: String? = null,
        onEvent: suspend (TtsStreamEvent) -> Unit,
    ) {
        // Always explicit, including 1.0: kobbler falls back to the active
        // preset's stored speed when the field is absent, so omitting it would
        // let a 1.5x preset override a reader who deliberately chose 1x.
        // Dropping it at natural rate is the ENGINE hop's job, not this one.
        val reqBody = json.encodeToString(
            TtsSynthesizeRequest.serializer(),
            TtsSynthesizeRequest(text, voice, speed, bookId),
        )
        httpClient.preparePost("$apiUrl/api/v1/admin/tts/synthesize") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(reqBody)
            // The request timeout spans the whole `execute` block, streamed events
            // included, so any finite budget here caps chapter length.
            timeout {
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                socketTimeoutMillis = BookFetchTimeoutMs
            }
        }.execute { readTtsEvents(it, onEvent) }
    }

    /** Render [text] for a cast receiver, returning the render's id. Same body as a read. */
    suspend fun startTtsCast(text: String, voice: String? = null, speed: Double? = null, bookId: String? = null): String {
        val body = httpClient.post("$apiUrl/api/v1/admin/tts/cast") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(TtsSynthesizeRequest.serializer(), TtsSynthesizeRequest(text, voice, speed, bookId)))
        }.checkedBody()
        return (json.parseToJsonElement(body) as JsonObject)["id"]!!.jsonPrimitive.content
    }

    /** What a receiver holding [token] plays for the cast render [id]. */
    fun ttsCastAudioUrl(id: String, token: String): String =
        withPlaybackToken("$apiUrl/api/v1/admin/tts/cast/$id/audio", token)

    /** The cast render's timeline: what [streamTtsSynthesize] sends, without the audio. */
    suspend fun streamTtsCastEvents(id: String, onEvent: suspend (TtsStreamEvent) -> Unit) {
        httpClient.prepareGet("$apiUrl/api/v1/admin/tts/cast/$id/events") {
            header(HttpHeaders.Authorization, "Bearer $token")
            timeout {
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                socketTimeoutMillis = BookFetchTimeoutMs
            }
        }.execute { readTtsEvents(it, onEvent) }
    }

    private suspend fun readTtsEvents(response: HttpResponse, onEvent: suspend (TtsStreamEvent) -> Unit) {
        if (!response.status.isSuccess()) {
            throw ApiException(
                response.status.value,
                ttsErrorMessage(response.status.value, response.status.description, response.bodyAsText()),
            )
        }
        val channel = response.bodyAsChannel()
        val framer = JsonObjectFramer()
        val chunk = ByteArray(TtsStreamChunkBytes)
        while (true) {
            val read = channel.readAvailable(chunk, 0, chunk.size)
            if (read < 0) break
            if (read == 0) continue
            for (obj in framer.feed(chunk, read)) {
                onEvent(decodeTtsEvent(obj) ?: continue)
            }
        }
    }

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    private fun decodeTtsEvent(line: String): TtsStreamEvent? {
        val obj = try {
            json.parseToJsonElement(line) as? JsonObject ?: return null
        } catch (_: Exception) {
            return null
        }

        // Every field is read through `as? JsonPrimitive` rather than `.jsonPrimitive`,
        // which throws on anything else. A field that arrives as an object or an array is
        // a shape this build does not know, and the answer to that is to ignore it.
        fun prim(key: String): JsonPrimitive? = obj[key] as? JsonPrimitive
        val type = prim("type")?.contentOrNull ?: return null

        fun int(key: String): Int? = prim(key)?.contentOrNull?.toIntOrNull()
        fun str(key: String): String? = prim(key)?.contentOrNull
        fun bool(key: String): Boolean? = prim(key)?.booleanOrNull
        fun dbl(key: String): Double? = prim(key)?.contentOrNull?.toDoubleOrNull()

        return when (type) {
            "sentence" -> TtsStreamEvent.Sentence(
                index = int("index") ?: 0,
                text = str("text") ?: "",
                audioB64 = str("audio_b64") ?: "",
                // Absent on older backends, which only ever rendered at 1.0.
                speed = dbl("speed") ?: 1.0,
                audioOffsetMs = int("audio_offset_ms"),
                durationMs = int("duration_ms"),
            )
            "paragraph_start" -> TtsStreamEvent.ParagraphStart(
                index = int("index") ?: 0,
                text = str("text") ?: "",
                wordOffset = int("word_offset") ?: 0,
                audioOffsetMs = int("audio_offset_ms") ?: 0,
                sampleRate = int("sample_rate") ?: 24000,
                // Absent on older backends, which only ever rendered at 1.0.
                speed = dbl("speed") ?: 1.0,
            )
            "audio" -> {
                val pcmB64 = str("pcm_b64") ?: return TtsStreamEvent.Other
                val pcm = try {
                    kotlin.io.encoding.Base64.decode(pcmB64)
                } catch (_: Exception) {
                    return TtsStreamEvent.Other
                }
                TtsStreamEvent.AudioPcm(
                    paragraphIndex = int("paragraph_index") ?: 0,
                    pcm = pcm,
                )
            }
            "chunk" -> TtsStreamEvent.Chunk(
                paragraphIndex = int("paragraph_index") ?: 0,
                index = int("index") ?: 0,
                chunksTotal = int("chunks_total") ?: 0,
                wordOffset = int("word_offset") ?: 0,
                wordCount = int("word_count") ?: 0,
                audioOffsetMs = int("audio_offset_ms") ?: 0,
                text = str("text") ?: "",
            )
            "alignment_partial" -> TtsStreamEvent.AlignmentPartial(
                paragraphIndex = int("paragraph_index") ?: 0,
                audioSeenMs = int("audio_seen_ms") ?: 0,
                words = parseWords(obj["words"]),
            )
            "alignment_final" -> TtsStreamEvent.AlignmentFinal(
                paragraphIndex = int("paragraph_index") ?: 0,
                audioTotalMs = int("audio_total_ms") ?: 0,
                words = parseWords(obj["words"]),
            )
            "error" -> TtsStreamEvent.Error(
                paragraphIndex = int("paragraph_index"),
                message = str("message") ?: "TTS error",
                fatal = bool("fatal") ?: false,
            )
            "done" -> TtsStreamEvent.Done
            else -> TtsStreamEvent.Other
        }
    }

    private fun parseWords(element: kotlinx.serialization.json.JsonElement?): List<TtsWordTiming> {
        val arr = element as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            try {
                json.decodeFromJsonElement(TtsWordTiming.serializer(), el)
            } catch (_: Exception) {
                null
            }
        }
    }

    // -- Calendar --

    suspend fun getCalendar(from: String, to: String): List<CalendarItemDto> =
        decode<CalendarResponse>(get("/api/v1/admin/calendar?from=$from&to=$to")).items

    // -- Search --

    suspend fun search(query: String, limit: Int = 10): SearchResponse =
        decode(get("/api/v1/admin/search?q=${query.encodeURLParameter()}&limit=$limit"))

    suspend fun searchSuggestions(query: String, mediaTypes: Set<String>, limit: Int = 8): List<SearchSuggestion> =
        decode<SearchSuggestionsResponse>(
            get("/api/v1/admin/search/suggestions?q=${query.encodeURLParameter()}&media=${mediaTypes.joinToString(",")}&limit=$limit"),
        ).suggestions

    private suspend fun searchExternalMovies(query: String): List<DiscoverItem> {
        val provider = topVideoProvider("movie_search")
        val providerQs = provider?.let { "&provider=${it.encodeURLParameter()}" } ?: ""
        val resp = get("/api/v1/admin/video-metadata/search/movies?q=${query.encodeURLParameter()}$providerQs")
        return decodeMap<ExternalMovieSearchResponse, List<DiscoverItem>>(resp) { r ->
            r.results.map { it.toDiscoverItem(apiUrl, "movie") }
        }
    }

    private suspend fun searchExternalTv(query: String): List<DiscoverItem> {
        val provider = topVideoProvider("tv_search")
        val providerQs = provider?.let { "&provider=${it.encodeURLParameter()}" } ?: ""
        val resp = get("/api/v1/admin/video-metadata/search/tv?q=${query.encodeURLParameter()}$providerQs")
        return decodeMap<ExternalTvSearchResponse, List<DiscoverItem>>(resp) { r ->
            r.results.map { it.toDiscoverItem(apiUrl, "tv") }
        }
    }

    private suspend fun searchExternalBooks(query: String): List<DiscoverItem> {
        val provider = topBookProvider("title_search")
        val providerQs = provider?.let { "&provider=${it.encodeURLParameter()}" } ?: ""
        val resp = get("/api/v1/admin/metadata/search?title=${query.encodeURLParameter()}$providerQs")
        return decodeMap<ExternalBookSearchResponse, List<DiscoverItem>>(resp) { results ->
            results.results.map { r ->
                        DiscoverItem(
                            provider = r.provider ?: "hardcover",
                            providerId = r.externalId ?: "",
                            mediaType = "book",
                            title = r.title ?: "",
                            year = null,
                            description = r.description,
                            posterUrl = resolveUrl(apiUrl, r.coverUrl),
                            backdropUrl = null,
                            genres = r.subjects ?: emptyList(),
                            rating = r.rating?.toFloat(),
                            ratingCount = r.ratingCount,
                            authors = r.authors ?: emptyList(),
                            libraryId = null,
                            wantStatus = null,
                            isInLibrary = false,
                        )
            }
        }
    }

    /** Search external providers in parallel, limited to [mediaTypes] ("movie", "tv", "book"). */
    suspend fun searchDiscover(
        query: String,
        mediaTypes: Set<String> = setOf("movie", "tv", "book"),
    ): DiscoverSearchResults = coroutineScope {
        val movies: kotlinx.coroutines.Deferred<List<DiscoverItem>>? = if ("movie" in mediaTypes) {
            async { quiet(emptyList<DiscoverItem>()) { searchExternalMovies(query) } }
        } else null
        val tv: kotlinx.coroutines.Deferred<List<DiscoverItem>>? = if ("tv" in mediaTypes) {
            async { quiet(emptyList<DiscoverItem>()) { searchExternalTv(query) } }
        } else null
        val books: kotlinx.coroutines.Deferred<List<DiscoverItem>>? = if ("book" in mediaTypes) {
            async { quiet(emptyList<DiscoverItem>()) { searchExternalBooks(query) } }
        } else null
        DiscoverSearchResults(
            movies?.await() ?: emptyList(),
            tv?.await() ?: emptyList(),
            books?.await() ?: emptyList(),
        )
    }

    // -- Provider resolution --
    // Cache provider lists so we don't refetch on every keystroke. Client-side
    // "highest-priority" selection keeps results from one provider only —
    // faster and avoids mixed-ranking noise from aggregated results.

    private var videoProvidersCache: List<ProviderInfoDto>? = null
    private var bookProvidersCache: List<ProviderInfoDto>? = null

    private suspend fun videoProviders(): List<ProviderInfoDto> {
        videoProvidersCache?.let { return it }
        return quiet(emptyList<ProviderInfoDto>()) {
            decode<List<ProviderInfoDto>>(get("/api/v1/admin/video-metadata/providers"))
                .also { videoProvidersCache = it }
        }
    }

    private suspend fun bookProviders(): List<ProviderInfoDto> {
        bookProvidersCache?.let { return it }
        return quiet(emptyList<ProviderInfoDto>()) {
            decode<List<ProviderInfoDto>>(get("/api/v1/admin/metadata/providers"))
                .also { bookProvidersCache = it }
        }
    }

    /** Provider type string (e.g. "tmdb") with the given capability and lowest priority, or null. */
    private suspend fun topVideoProvider(capability: String): String? =
        videoProviders()
            .filter { it.enabled && capability in it.capabilities }
            .minByOrNull { it.priority }
            ?.providerType

    private suspend fun topBookProvider(capability: String): String? =
        bookProviders()
            .filter { it.enabled && capability in it.capabilities }
            .minByOrNull { it.priority }
            ?.providerType

    // -- IPTV --

    data class IptvChannelPage(val channels: List<IptvChannel>, val total: Int)

    suspend fun getIptvChannels(
        search: String? = null,
        category: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): IptvChannelPage {
        val path = buildString {
            append("/api/v1/admin/iptv/channels?enabled=true&limit=$limit&offset=$offset")
            if (search != null) append("&search=${search.encodeURLParameter()}")
            if (category != null) append("&category=${category.encodeURLParameter()}")
        }
        return decodeMap<IptvChannelListResponse, IptvChannelPage>(get(path)) { resp ->
            IptvChannelPage(resp.items.map { it.toModel(apiUrl) }, resp.total)
        }
    }

    suspend fun getIptvChannel(id: String): IptvChannel {
        val dto = decode<IptvChannelDto>(get("/api/v1/admin/iptv/channels/$id"))
        return dto.toModel(apiUrl)
    }

    suspend fun getIptvGroups(): List<IptvGroup> =
        decodeMap<List<IptvGroupDto>, List<IptvGroup>>(get("/api/v1/admin/iptv/groups")) { groups ->
            groups.filter { it.enabled }.sortedBy { it.displayOrder }.map {
                IptvGroup(id = it.id, name = it.name, slug = it.slug, iconUrl = it.icon, channelCount = it.channelCount)
            }
        }

    suspend fun getIptvEpgNow(): List<IptvNowNextDto> =
        decode<List<IptvNowNextDto>>(get("/api/v1/admin/iptv/epg/now"))

    suspend fun getIptvChannelEpg(channelId: String): List<IptvEpgEntry> =
        decodeMap<List<IptvEpgEntryDto>, List<IptvEpgEntry>>(get("/api/v1/admin/iptv/channels/$channelId/epg")) { epg ->
            epg.map { IptvEpgEntry(id = it.id, title = it.title, description = it.description, startTime = it.startTime, stopTime = it.stopTime, categories = it.categories) }
        }

    suspend fun playIptvChannel(channelId: String): IptvLiveSessionDto {
        val body = buildJsonObject {
            put("channel_id", channelId)
            put("capabilities", capabilitiesJson())
        }
        return decode<IptvLiveSessionDto>(post("/api/v1/admin/iptv/sessions", body.toString()))
    }

    suspend fun getIptvFavourites(): List<IptvFavouriteDto> =
        decode<List<IptvFavouriteDto>>(get("/api/v1/admin/iptv/favourites"))

    suspend fun addIptvFavourite(channelId: String) {
        post("/api/v1/admin/iptv/favourites", """{"channel_id":"$channelId"}""")
        DataCache.invalidate("iptv-header")
    }

    suspend fun removeIptvFavourite(favouriteId: String) {
        delete("/api/v1/admin/iptv/favourites/$favouriteId")
        DataCache.invalidate("iptv-header")
    }

    /** Get favourite channels with their EPG programs. Fast — only fetches favourites, not all channels. */
    suspend fun getIptvEpgFavourites(): IptvEpgFavouritesResponse =
        decode<IptvEpgFavouritesResponse>(get("/api/v1/admin/iptv/epg/favourites"))

    /** Get watch pattern matches — upcoming programs matching user-defined watch rules. */
    suspend fun getIptvWatchMatches(): List<IptvWatchMatchDto> =
        decode<List<IptvWatchMatchDto>>(get("/api/v1/admin/iptv/watches/matches"))

    // -- Live TV (tuner) --

    data class LiveChannelPage(
        val channels: List<LiveChannel>,
        val total: Int,
        val facets: LiveFacets,
    )

    suspend fun getLiveTuners(): List<LiveTuner> =
        decodeMap<LiveTunerListResponse, List<LiveTuner>>(get("/api/v1/admin/livetv/tuners")) { resp ->
            resp.items.map {
                LiveTuner(
                    id = it.id,
                    friendlyName = it.friendlyName ?: it.deviceId ?: "Tuner",
                    modelNumber = it.modelNumber,
                    tunerCount = it.tunerCount,
                    channelCount = it.channelCount,
                    enabledChannelCount = it.enabledChannelCount,
                    leasesInUse = it.leasesInUse,
                    lastScanAt = it.lastScanAt,
                    lastGuideSyncAt = it.lastGuideSyncAt,
                )
            }
        }

    suspend fun getLiveChannels(
        search: String? = null,
        network: String? = null,
        region: String? = null,
        kind: String? = null,
        hd: Boolean? = null,
        enabled: Boolean? = true,
        favourite: Boolean? = null,
        hasGuide: Boolean? = null,
        mux: String? = null,
        sort: String? = null,
        withNow: Boolean = false,
        limit: Int = 50,
        offset: Int = 0,
    ): LiveChannelPage {
        val path = buildString {
            // Stacked: one row per programme feed, the server picking the best copy and
            // falling back to the others when it will not tune.
            append("/api/v1/admin/livetv/channels?limit=$limit&offset=$offset&stacked=true")
            if (search != null) append("&search=${search.encodeURLParameter()}")
            if (network != null) append("&network=${network.encodeURLParameter()}")
            if (region != null) append("&region=${region.encodeURLParameter()}")
            if (kind != null) append("&kind=${kind.encodeURLParameter()}")
            if (hd != null) append("&hd=$hd")
            if (enabled != null) append("&enabled=$enabled")
            if (favourite != null) append("&favourite=$favourite")
            if (hasGuide != null) append("&has_guide=$hasGuide")
            if (mux != null) append("&mux=${mux.encodeURLParameter()}")
            if (sort != null) append("&sort=${sort.encodeURLParameter()}")
            if (withNow) append("&with_now=true")
        }
        return decodeMap<LiveChannelListResponse, LiveChannelPage>(get(path)) { resp ->
            LiveChannelPage(
                channels = resp.items.map { it.toModel(apiUrl) },
                total = resp.total,
                facets = resp.facets.toModel(),
            )
        }
    }

    suspend fun setLiveChannelFavourite(channelId: String, favourite: Boolean): LiveChannel {
        val dto = decode<LiveChannelDto>(
            patch("/api/v1/admin/livetv/channels/$channelId", """{"favourite":$favourite}"""),
        )
        DataCache.invalidate("livetv-header")
        return dto.toModel(apiUrl)
    }

    suspend fun getLiveGuide(
        from: String? = null,
        to: String? = null,
        channelIds: List<String>? = null,
        search: String? = null,
        genre: String? = null,
    ): List<LiveGuideChannel> {
        val path = buildString {
            append("/api/v1/admin/livetv/guide?")
            val parts = mutableListOf<String>()
            if (from != null) parts += "from=${from.encodeURLParameter()}"
            if (to != null) parts += "to=${to.encodeURLParameter()}"
            if (!channelIds.isNullOrEmpty()) parts += "channel_ids=${channelIds.joinToString(",").encodeURLParameter()}"
            else parts += "stacked=true"
            if (search != null) parts += "search=${search.encodeURLParameter()}"
            if (genre != null) parts += "genre=${genre.encodeURLParameter()}"
            append(parts.joinToString("&"))
        }
        return decodeMap<LiveGuideResponse, List<LiveGuideChannel>>(get(path)) { resp ->
            resp.channels.map { LiveGuideChannel(it.channel.toModel(apiUrl), it.entries.map { e -> e.toModel(apiUrl) }) }
        }
    }

    suspend fun getLiveGuideNow(channelIds: List<String>? = null, limit: Int? = null): List<LiveChannel> {
        val path = buildString {
            append("/api/v1/admin/livetv/guide/now?")
            val parts = mutableListOf<String>()
            if (!channelIds.isNullOrEmpty()) parts += "channel_ids=${channelIds.joinToString(",").encodeURLParameter()}"
            else parts += "stacked=true"
            if (limit != null) parts += "limit=$limit"
            append(parts.joinToString("&"))
        }
        return decodeMap<LiveGuideNowResponse, List<LiveChannel>>(get(path)) { resp ->
            resp.items.mapNotNull { it.toChannelModel(apiUrl) }
        }
    }

    suspend fun getLiveGuideEntry(id: String): LiveGuideEntry =
        decodeMap<LiveGuideEntryDto, LiveGuideEntry>(get("/api/v1/admin/livetv/guide/$id")) { it.toModel(apiUrl) }

    suspend fun getLiveOnNow(): LiveOnNow =
        decodeMap<LiveOnNowResponse, LiveOnNow>(get("/api/v1/admin/livetv/on-now?stacked=true")) { resp ->
            LiveOnNow(
                generatedAt = resp.generatedAt,
                tunerNotice = resp.tunerNotice?.let {
                    LiveClashNotice(
                        guide = it.guide.toModel(apiUrl),
                        reason = it.reason,
                        alternative = it.alternative?.toModel(apiUrl),
                    )
                },
                groups = resp.groups.map { group ->
                    LiveOnNowGroup(
                        key = group.key,
                        title = group.title,
                        subtitle = group.subtitle,
                        items = group.items.map { item ->
                            LiveOnNowItem(
                                channel = item.channel.toModel(apiUrl),
                                guide = item.guide.toModel(apiUrl),
                                minutesLeft = item.minutesLeft,
                                startsInMinutes = item.startsInMinutes,
                                reason = item.reason,
                                confidence = item.confidence,
                            )
                        },
                    )
                },
            )
        }

    suspend fun getLiveLeases(): LiveLeases =
        decodeMap<LiveLeaseListResponse, LiveLeases>(get("/api/v1/admin/livetv/leases")) { resp ->
            LiveLeases(
                total = resp.total,
                inUse = resp.inUse,
                items = resp.items.map {
                    LiveLease(
                        id = it.id, channelId = it.channelId, channelName = it.channelName,
                        tunerIndex = it.tunerIndex ?: 0, holderKind = it.holderKind,
                        recordingId = it.recordingId, acquiredAt = it.acquiredAt, expiresAt = it.expiresAt,
                    )
                },
            )
        }

    data class LiveRecordingPage(val recordings: List<LiveRecording>, val total: Int)

    suspend fun getLiveRecordings(
        status: String? = null,
        limit: Int = 100,
        offset: Int = 0,
    ): LiveRecordingPage {
        val path = buildString {
            append("/api/v1/admin/livetv/recordings?limit=$limit&offset=$offset")
            if (status != null) append("&status=${status.encodeURLParameter()}")
        }
        return decodeMap<LiveRecordingListResponse, LiveRecordingPage>(get(path)) { resp ->
            LiveRecordingPage(resp.items.map { it.toModel() }, resp.total)
        }
    }

    suspend fun getLiveRecording(id: String): LiveRecording =
        decodeMap<LiveRecordingDto, LiveRecording>(get("/api/v1/admin/livetv/recordings/$id")) { it.toModel() }

    suspend fun recordLiveGuideEntry(guideId: String): LiveRecording {
        val body = decodeMap<LiveRecordingDto, LiveRecording>(
            post("/api/v1/admin/livetv/recordings", """{"guide_id":"$guideId"}"""),
        ) { it.toModel() }
        DataCache.invalidate("livetv-recordings")
        return body
    }

    suspend fun stopLiveRecording(id: String): LiveRecording {
        val rec = decodeMap<LiveRecordingDto, LiveRecording>(
            post("/api/v1/admin/livetv/recordings/$id/stop", "{}"),
        ) { it.toModel() }
        DataCache.invalidate("livetv-recordings")
        return rec
    }

    suspend fun deleteLiveRecording(id: String) {
        delete("/api/v1/admin/livetv/recordings/$id")
        DataCache.invalidate("livetv-recordings")
    }

    suspend fun getLiveSeriesRules(): List<LiveSeriesRule> =
        decodeMap<LiveSeriesRuleListResponse, List<LiveSeriesRule>>(get("/api/v1/admin/livetv/series-rules")) { resp ->
            resp.items.map { it.toModel() }
        }

    suspend fun createLiveSeriesRule(rule: LiveSeriesRule): LiveSeriesRule {
        val saved = decodeMap<LiveSeriesRuleDto, LiveSeriesRule>(
            post("/api/v1/admin/livetv/series-rules", rule.toInputJson()),
        ) { it.toModel() }
        DataCache.invalidate("livetv-series-rules")
        return saved
    }

    suspend fun updateLiveSeriesRule(rule: LiveSeriesRule): LiveSeriesRule {
        val saved = decodeMap<LiveSeriesRuleDto, LiveSeriesRule>(
            patch("/api/v1/admin/livetv/series-rules/${rule.id}", rule.toInputJson()),
        ) { it.toModel() }
        DataCache.invalidate("livetv-series-rules")
        return saved
    }

    suspend fun deleteLiveSeriesRule(id: String) {
        delete("/api/v1/admin/livetv/series-rules/$id")
        DataCache.invalidate("livetv-series-rules")
    }

    suspend fun previewLiveSeriesRule(rule: LiveSeriesRule): LiveRulePreview =
        decodeMap<LiveRulePreviewResponse, LiveRulePreview>(
            post("/api/v1/admin/livetv/series-rules/preview", rule.toInputJson()),
        ) { resp ->
            LiveRulePreview(
                clusters = resp.clusters.map { c ->
                    LiveCluster(
                        label = c.label, startTime = c.startTime,
                        members = c.members.map { m ->
                            LiveClusterMember(
                                guideId = m.guideId, channelId = m.channelId, channelName = m.channelName,
                                lcn = m.lcn?.toString(), hd = m.hd, startTime = m.startTime,
                                record = m.action == "record", reason = m.reason,
                            )
                        },
                    )
                },
                wouldRecord = resp.wouldRecord,
                suppressed = resp.suppressed,
            )
        }

    private fun LiveSeriesRule.toInputJson(): String = buildJsonObject {
        seriesId?.let { put("series_id", it) }
        put("title", title)
        put("enabled", enabled)
        putJsonArray("channel_ids") { channelIds.forEach { add(JsonPrimitive(it)) } }
        putJsonArray("weekdays") { weekdays.forEach { add(JsonPrimitive(it)) } }
        put("window_start", windowStart)
        put("window_end", windowEnd)
        put("new_only", newOnly)
        put("min_season", minSeason)
        put("keep_count", keepCount)
        put("keep_until", keepUntil)
        put("padding_before_secs", paddingBeforeSecs)
        put("padding_after_secs", paddingAfterSecs)
        put("on_clash", onClash)
        put("priority", priority)
    }.toString()

    private fun capabilities() =
        browserCapabilities() ?: VLC_CAPABILITIES.copy(supportsInterlaced = decodesInterlaced(), hdrFormats = hdrFormats())

    private fun capabilitiesJson() = json.encodeToJsonElement(ClientCapabilities.serializer(), capabilities())

    suspend fun openLiveSession(channelId: String): LiveSession {
        val body = buildJsonObject {
            put("channel_id", channelId)
            put("capabilities", capabilitiesJson())
        }
        return decodeMap<LiveSessionDto, LiveSession>(
            post("/api/v1/admin/livetv/sessions", body.toString()),
        ) { it.toModel(apiUrl) }
    }

    // Without capabilities the server falls back to its conservative default —
    // H.264 in MP4 — and transcodes a channel the player could have taken raw.
    suspend fun switchLiveSessionChannel(
        sessionId: String,
        channelId: String,
    ): LiveSession {
        val body = buildJsonObject {
            put("channel_id", channelId)
            put("capabilities", capabilitiesJson())
        }
        return decodeMap<LiveSessionDto, LiveSession>(
            post("/api/v1/admin/livetv/sessions/$sessionId/channel", body.toString()),
        ) { it.toModel(apiUrl) }
    }

    /**
     * Hand a playback failure to the server. The player knows things the server
     * cannot — which URL its decoder was actually given, the device, the build —
     * and before this a hard failure left no trace anywhere but a screenshot.
     */
    suspend fun reportPlaybackFailure(
        detail: String,
        mediaType: String?,
        mediaId: String?,
        streamUrl: String?,
        sessionId: String?,
    ) {
        val body = buildJsonObject {
            put("detail", detail)
            put("client_kind", clientKind)
            put("app_version", "${AppVersion.versionName} (${AppVersion.versionCode})")
            put("device_label", deviceLabel)
            mediaType?.let { put("media_type", it) }
            mediaId?.let { put("media_id", it) }
            streamUrl?.let { put("stream_url", it) }
            sessionId?.let { put("session_id", it) }
        }.toString()
        post("/api/v1/admin/diagnostics/playback", body)
    }

    suspend fun closeLiveSession(sessionId: String) {
        quiet(Unit) { delete("/api/v1/admin/livetv/sessions/$sessionId") }
    }

    suspend fun getLiveSessionBuffer(sessionId: String): LiveBuffer =
        decodeMap<LiveBufferDto, LiveBuffer>(get("/api/v1/admin/livetv/sessions/$sessionId/buffer")) {
            LiveBuffer(
                startTime = it.startTime,
                endTime = it.endTime,
                liveEdgeSeconds = it.liveEdgeSeconds,
                durationSeconds = it.durationSeconds,
                retainedSeconds = it.retainedSeconds,
                capSeconds = it.capSeconds,
                tuneError = it.tuneError,
            )
        }

    suspend fun liveSessionHeartbeat(sessionId: String) {
        post("/api/v1/admin/livetv/sessions/$sessionId/heartbeat", """{"source":"kobapp"}""")
    }

    suspend fun recordLiveSession(sessionId: String, guideId: String?): LiveRecording {
        val body = buildJsonObject {
            put("mode", if (guideId != null) "programme" else "open_ended")
            guideId?.let { put("guide_id", it) }
        }
        val rec = decodeMap<LiveRecordingDto, LiveRecording>(
            post("/api/v1/admin/livetv/sessions/$sessionId/record", body.toString()),
        ) { it.toModel() }
        DataCache.invalidate("livetv-recordings")
        return rec
    }

    suspend fun getLiveRecordingPlayback(recordingId: String): StreamSession? {
        return decode<SessionResponse>(get("/api/v1/admin/livetv/recordings/$recordingId/playback")).toStreamSession()
    }

    private fun LiveSessionDto.toModel(base: String): LiveSession {
        // The session pre-opens the tuner and stream.ts consumes that open connection.
        // Without ?session= the route opens a second one and burns a second physical
        // tuner, so the server's own url is used verbatim and only repaired if absent.
        val direct = directUrl?.let { if (it.contains("session=")) it else appendSessionParam(it, sessionId) }
        return LiveSession(
            sessionId = sessionId,
            channelId = channelId,
            method = method ?: "direct",
            directUrl = direct?.let { resolveUrl(base, it) },
            playlistUrl = playlistUrl?.let { resolveUrl(base, it) },
            videoCodec = videoCodec,
            audioCodec = audioCodec,
            hd = hd,
            interlaced = interlaced,
            thumbnailsUrl = thumbnailsUrl?.let { resolveUrl(base, it) },
            width = width,
            height = height,
            bufferStartTime = bufferStartTime,
            tunersInUse = tunersInUse,
            tunerCount = tunerCount,
            now = now?.toModel(base),
            next = next?.toModel(base),
        )
    }

    private fun LiveChannelDto.toModel(base: String): LiveChannel = LiveChannel(
        id = id,
        name = name.ifBlank { displayName ?: scanName ?: callsign ?: "Unknown" },
        lcn = lcn?.toString(),
        callsign = callsign,
        logoUrl = resolveUrlOrNull(base, logoUrl),
        network = network,
        region = region,
        kind = kind,
        hd = hd,
        enabled = enabled,
        favourite = favourite,
        muxLabel = muxLabel,
        typeLabel = typeLabel,
        hasGuide = hasGuide,
        guideEntryCount = guideEntryCount,
        videoCodec = videoCodec,
        audioCodec = audioCodec,
        signalQuality = signalQuality,
        now = now?.now?.toModel(base),
        next = now?.next?.toModel(base),
        minutesLeft = now?.minutesLeft,
    )

    private fun LiveGuideNowDto.toChannelModel(base: String): LiveChannel? {
        val c = channel ?: return null
        return c.toModel(base).copy(
            now = now?.toModel(base),
            next = next?.toModel(base),
            minutesLeft = minutesLeft,
        )
    }

    private fun LiveFacetsDto.toModel(): LiveFacets {
        fun List<LiveFacetDto>.m() = map { LiveFacet(it.value, it.label.ifBlank { it.value }, it.count) }
        return LiveFacets(network.m(), region.m(), type.m(), guide.m(), status.m(), mux.m())
    }

    private fun LiveGuideEntryDto.toModel(base: String) = LiveGuideEntry(
        id = id,
        channelId = channelId,
        startTime = startTime,
        endTime = endTime,
        title = title,
        episodeNumber = episodeNumber,
        episodeTitle = episodeTitle,
        synopsis = synopsis,
        genres = genres,
        imageUrl = resolveUrlOrNull(base, imageUrl),
        posterUrl = resolveUrlOrNull(base, posterUrl),
        isFirst = isFirst,
        episodeKey = episodeKey,
        durationSeconds = durationSeconds,
        progressPct = progressPct,
        recordingId = recording?.id,
        recordingStatus = recording?.status,
        repeatCount = repeatCount,
    )

    private fun LiveRecordingDto.toModel() = LiveRecording(
        id = id,
        channelId = channelId,
        channelName = channelName,
        guideId = guideId,
        seriesRuleId = seriesRuleId,
        title = title,
        episodeNumber = episodeNumber,
        episodeTitle = episodeTitle,
        synopsis = synopsis,
        scheduledStart = scheduledStart,
        scheduledEnd = scheduledEnd,
        actualStart = actualStart,
        actualEnd = actualEnd,
        status = status,
        tunerIndex = tunerIndex,
        durationSeconds = durationSeconds?.toLong() ?: 0L,
        videoCodec = videoCodec,
        audioCodec = audioCodec,
        sizeBytes = sizeBytes ?: 0L,
        movedToGuideId = movedToGuideId,
        imageUrl = resolveUrlOrNull(apiUrl, imageUrl),
        episodeId = episodeId,
        movieId = movieId,
        error = error,
        isInProgress = isInProgress,
        secondsOnDisk = secondsOnDisk.toLong(),
        playableFromStart = playableFromStart,
        adBreaks = adBreaks.map {
            LiveAdBreak(
                id = it.id, startSeconds = it.startSeconds, endSeconds = it.endSeconds,
                method = it.method, confidence = it.confidence,
                adsKnown = it.adsKnown, adsTotal = it.adsTotal, correctedAt = it.correctedAt,
            )
        },
        provenance = provenance?.let { p ->
            val where = p.channelName ?: return@let null
            listOfNotNull("recorded from $where", p.aired).joinToString(", ")
        },
        adPosture = resolveAdPosture(adPosture),
        adSkipDisabled = adSkipDisabled,
    )

    private fun LiveSeriesRuleDto.toModel() = LiveSeriesRule(
        id = id,
        seriesId = seriesId,
        title = title,
        enabled = enabled,
        channelIds = channelIds,
        weekdays = weekdays,
        windowStart = windowStart,
        windowEnd = windowEnd,
        newOnly = newOnly,
        minSeason = minSeason,
        keepCount = keepCount,
        keepUntil = keepUntil,
        paddingBeforeSecs = paddingBeforeSecs,
        paddingAfterSecs = paddingAfterSecs,
        onClash = onClash,
        priority = priority,
        queuedCount = queuedCount,
    )

    private fun IptvChannelDto.toModel(base: String) = IptvChannel(
        id = id, name = name, iconUrl = resolveUrlOrNull(base, iconUrl),
        category = category, language = language, country = country,
        sourceCount = sourceCount,
    )

    private fun StreamOptionDto.toModel(): StreamOption {
        val q = quality
        fun qStr(vararg keys: String) = keys.firstNotNullOfOrNull { q?.get(it)?.jsonPrimitive?.contentOrNull }
        val file = files.firstOrNull()
        return StreamOption(
            id = id, releaseTitle = releaseTitle, size = size, indexer = indexer,
            infoHash = infoHash,
            quality = StreamQuality(
                resolution = qStr("resolution", "source"), codec = qStr("codec", "video_codec"),
                hdr = qStr("hdr", "dynamic_range")?.takeUnless { it.equals("sdr", ignoreCase = true) },
                audio = qStr("audio", "audio_codec"),
            ),
            qualityJson = q,
            totalScore = totalScore, debridProvider = debridProvider,
            debridProviderId = debridProviderId, cached = cached, sourceType = sourceType,
            fileId = file?.id, publishDate = publishDate,
            fileName = file?.path?.substringAfterLast('/')?.takeIf { it.isNotBlank() },
            playback = playback,
            availability = availability,
            group = group,
            audioLanguages = audioLanguages,
        )
    }

    // -- People --

    suspend fun getPerson(id: String): Person {
        val dto = decode<PersonDto>(get("/api/v1/admin/people/$id"))
        return Person(
            id = dto.id, name = dto.name,
            imageUrl = resolveUrlOrNull(apiUrl, dto.imageUrl),
            bio = dto.bio, birthDate = dto.birthDate, deathDate = dto.deathDate,
            knownForDepartment = dto.knownForDepartment, placeOfBirth = dto.placeOfBirth,
        )
    }

    suspend fun getPersonFilmography(id: String): List<FilmographyCredit> =
        decodeMap<FilmographyResponseDto, List<FilmographyCredit>>(get("/api/v1/admin/people/$id/filmography")) { resp ->
            resp.credits.filter { !it.dismissed }.map {
                FilmographyCredit(
                    providerId = it.providerId, mediaType = it.mediaType,
                    title = it.title, year = it.year,
                    posterUrl = resolveUrlOrNull(apiUrl, it.posterUrl),
                    character = it.character, department = it.department, job = it.job,
                    creditType = it.creditType, inLibrary = it.inLibrary,
                    libraryId = it.libraryId, wantStatus = it.wantStatus,
                    dismissed = it.dismissed,
                )
            }
        }

    // -- Wanted --

    suspend fun getWantedItems(): List<WantedItem> = coroutineScope {
        val booksDeferred = async {
            quiet(emptyList<WantedItem>()) {
                decodeMap<WantedListResponse, List<WantedItem>>(get("/api/v1/admin/wanted")) { resp ->
                    resp.items.map {
                        WantedItem(
                            id = it.id, title = it.title,
                            subtitle = it.author ?: it.series ?: "",
                            coverUrl = resolveUrl(apiUrl, it.coverUrl),
                            wantStatus = it.wantStatus, attemptStatus = it.attemptStatus,
                            mediaType = "book",
                        )
                    }
                }
            }
        }
        val moviesDeferred = async {
            quiet(emptyList<WantedItem>()) {
                decodeMap<PaginatedMovies, List<WantedItem>>(get("/api/v1/admin/movies?want_status=wanted&is_in_library=false")) { resp ->
                    resp.items.map {
                        WantedItem(
                            id = it.id, title = it.title,
                            subtitle = it.year?.toString() ?: "",
                            coverUrl = resolveUrl(apiUrl, it.posterUrl),
                            wantStatus = it.wantStatus, attemptStatus = null,
                            mediaType = "movie",
                        )
                    }
                }
            }
        }
        val showsDeferred = async {
            quiet(emptyList<WantedItem>()) {
                decodeMap<PaginatedShows, List<WantedItem>>(get("/api/v1/admin/tv/shows?monitored=true")) { resp ->
                    resp.items.map {
                        WantedItem(
                            id = it.id, title = it.title,
                            subtitle = it.network ?: "",
                            coverUrl = resolveUrl(apiUrl, it.posterUrl),
                            wantStatus = "monitored", attemptStatus = null,
                            mediaType = "tv_show",
                        )
                    }
                }
            }
        }
        booksDeferred.await() + moviesDeferred.await() + showsDeferred.await()
    }

    // -- Discover --

    suspend fun dismissRecommendation(item: DiscoverItem) {
        val tmdbId = item.providerId.toLongOrNull()?.takeIf { item.provider == "tmdb" } ?: return
        post("/api/v1/admin/discover/for-you/dismiss", recommendationBody(item.mediaType, tmdbId))
        DataCache.invalidate("home")
    }

    suspend fun excludeFromRecommendations(seeds: List<ForYouSeed>, excluded: Boolean = true) {
        val body = buildJsonObject {
            putJsonArray("items") {
                seeds.forEach { seed ->
                    add(buildJsonObject { put("media_type", seed.mediaType); put("tmdb_id", seed.tmdbId) })
                }
            }
            put("excluded", excluded)
        }
        post("/api/v1/admin/watch-history/exclude", body.toString())
        DataCache.invalidate("home")
    }

    private fun recommendationBody(mediaType: String, tmdbId: Long) =
        buildJsonObject { put("media_type", mediaType); put("tmdb_id", tmdbId) }.toString()

    suspend fun getDiscover(): HomeData = coroutineScope {
        val probe = async { quiet(Unit) { checkStreamingAvailability() } }
        val body = get("/api/v1/admin/discover")
        probe.await()
        decodeMap<DiscoverResponseDto, HomeData>(body) { resp ->
            HomeData(
                watchNext = resp.watchNext.map { it.toContinueItem(apiUrl) },
                recentlyAdded = resp.recentlyAdded.map { it.toRecentlyAddedItem(apiUrl) },
                stremioContinue = resp.stremioContinue.map { it.toStremioContinueItem(apiUrl) },
                forYou = resp.forYou.map { it.toForYouItem(apiUrl) },
                sections = resp.sections
                    .sortedBy { it.sortOrder }
                    .map { s -> DiscoverSection(
                        title = s.title,
                        sectionKey = s.sectionKey,
                        sortOrder = s.sortOrder,
                        items = s.items.map { it.toDiscoverItem(apiUrl) },
                    ) },
            )
        }
    }

    suspend fun getWatchNext(): List<InProgressItem> =
        decodeMap<WatchNextResponseDto, List<InProgressItem>>(get("/api/v1/admin/discover")) { it.watchNext }

    suspend fun getDiscoverDetail(mediaType: String, provider: String, providerId: String): DiscoverDetail {
        val respText = get("/api/v1/admin/discover/$mediaType/$provider/${providerId.encodeURLPathPart()}")
        return withContext(Dispatchers.Default) {
            val dto = json.decodeFromString<DiscoverDetailDto>(respText)
            val d = dto.details

            fun str(key: String): String? = d[key]?.jsonPrimitive?.takeIf { it.isString }?.content
            fun int(key: String): Int? = d[key]?.jsonPrimitive?.content?.toIntOrNull()
            fun dbl(key: String): Float? = d[key]?.jsonPrimitive?.content?.toDoubleOrNull()?.toFloat()
            fun strList(key: String): List<String> = try {
                d[key]?.let { json.decodeFromString<List<String>>(it.toString()) } ?: emptyList()
            } catch (_: Exception) { emptyList() }

            val cast = try {
                d["cast"]?.let { json.decodeFromString<List<JsonObject>>(it.toString()) }?.mapNotNull { c ->
                    val name = (c["name"] ?: c["person_name"])?.jsonPrimitive?.content ?: return@mapNotNull null
                    val char = (c["character"] ?: c["character_name"])?.jsonPrimitive?.content
                    val profileUrl = (c["profile_url"] ?: c["person_image_url"])?.jsonPrimitive?.content
                    CastMember(name = name, character = char, imageUrl = profileUrl)
                } ?: emptyList()
            } catch (_: Exception) { emptyList() }

            val authors = strList("authors").ifEmpty {
                try {
                    d["authors_with_ids"]?.let { json.decodeFromString<List<JsonObject>>(it.toString()) }
                        ?.mapNotNull { it["name"]?.jsonPrimitive?.content } ?: emptyList()
                } catch (_: Exception) { emptyList() }
            }

            val crew = try {
                d["crew"]?.let { json.decodeFromString<List<JsonObject>>(it.toString()) }?.mapNotNull { c ->
                    CrewDto(
                        personName = c["name"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                        department = c["department"]?.jsonPrimitive?.content ?: "",
                        job = c["job"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                    )
                } ?: emptyList()
            } catch (_: Exception) { emptyList() }

            val identifiers = try {
                d["identifiers"]?.jsonObject?.mapValues { (_, v) -> v.jsonPrimitive.content }
                    ?.filterValues { it != "null" } ?: emptyMap()
            } catch (_: Exception) { emptyMap() }

            val seasons = try {
                d["seasons"]?.let { json.decodeFromString<List<DiscoverSeasonDto>>(it.toString()) }
                    ?.map { DiscoverSeason(seasonNumber = it.seasonNumber, title = it.title, episodeCount = it.episodeCount, airDate = it.airDate) }
                    ?: emptyList()
            } catch (_: Exception) { emptyList() }

            DiscoverDetail(
                mediaType = dto.mediaType,
                provider = dto.provider,
                title = str("title") ?: "",
                year = int("year"),
                description = str("description"),
                posterUrl = resolveUrl(apiUrl, str("poster_url") ?: str("cover_url")),
                backdropUrl = resolveUrlOrNull(apiUrl, str("backdrop_url")),
                genres = strList("genres").ifEmpty { strList("subjects") },
                rating = dbl("rating"),
                cast = cast,
                network = str("network"),
                runtime = int("runtime"),
                authors = authors,
                libraryId = dto.libraryId,
                wantStatus = dto.wantStatus,
                isInLibrary = dto.isInLibrary ?: false,
                identifiers = identifiers,
                providerId = providerId,
                seasons = seasons,
                ratingCount = int("rating_count"),
                releaseDate = parseIsoDate(str("release_date")),
                releaseDates = runCatching {
                    (d["release_dates"] as? JsonObject)?.let { json.decodeFromJsonElement(ReleaseDatesDto.serializer(), it).toModel() }
                }.getOrNull(),
                tagline = str("tagline")?.ifBlank { null },
                contentRating = str("content_rating")?.ifBlank { null },
                directors = crew.directors(),
                writers = crew.writers(),
            )
        }
    }

    /** Want one episode of a show the library may not have yet; the server creates what is missing. */
    suspend fun wantDiscoverEpisode(detail: DiscoverDetail, episode: DiscoverEpisodeRef) {
        val body = discoverShowSpec(detail, episode.season) { put("episode_number", episode.episode) }
        post("/api/v1/admin/discover/want-episode", body.toString())
        invalidateTvShows()
    }

    /** The show's library id: wanting the season added the show. */
    suspend fun wantDiscoverSeason(detail: DiscoverDetail, season: Int): String {
        val resp = decode<CreateDiscoverSeasonWant>(post("/api/v1/admin/discover/want-season", discoverShowSpec(detail, season).toString()))
        invalidateTvShows()
        return resp.showId
    }

    private fun discoverShowSpec(detail: DiscoverDetail, season: Int, extra: JsonObjectBuilder.() -> Unit = {}) =
        buildJsonObject {
            put("provider", detail.provider)
            put("provider_id", detail.providerId)
            put("season_number", season)
            extra()
            put("title", detail.title)
            detail.year?.let { put("year", it) }
            if (detail.identifiers.isNotEmpty()) {
                putJsonObject("identifiers") { detail.identifiers.forEach { (k, v) -> put(k, v) } }
            }
            detail.description?.let { put("description", it) }
            detail.posterUrl.takeIf { it.isNotEmpty() }?.let { put("poster_url", it) }
            detail.backdropUrl?.let { put("backdrop_url", it) }
            if (detail.genres.isNotEmpty()) {
                putJsonArray("genres") { detail.genres.forEach { add(JsonPrimitive(it)) } }
            }
            detail.rating?.let { put("rating", it.toDouble()) }
            detail.runtime?.let { put("runtime", it) }
            detail.network?.let { put("network", it) }
        }

    /** Create a movie from discover data and mark it wanted. Returns the kobbler ID. */
    suspend fun wantMovie(detail: DiscoverDetail): String {
        val body = buildJsonObject {
            put("title", detail.title)
            detail.year?.let { put("year", it) }
            detail.description?.let { put("description", it) }
            detail.posterUrl.takeIf { it.isNotEmpty() }?.let { put("poster_url", it) }
            detail.backdropUrl?.let { put("backdrop_url", it) }
            if (detail.identifiers.isNotEmpty()) {
                putJsonObject("identifiers") { detail.identifiers.forEach { (k, v) -> put(k, v) } }
            }
            if (detail.genres.isNotEmpty()) {
                putJsonArray("genres") { detail.genres.forEach { add(JsonPrimitive(it)) } }
            }
            detail.rating?.let { put("rating", it.toDouble()) }
            detail.runtime?.let { put("runtime", it) }
        }
        val resp = decode<CreateEntityResponse>(post("/api/v1/admin/movies", body.toString()))
        post("/api/v1/admin/movies/${resp.id}/want", "{}")
        invalidateMovies()
        return resp.id
    }

    /**
     * Create a TV show from discover data and start wanting it. Returns the kobbler ID.
     *
     * [autoWantMode] is the server's own (`none` / `new_only` / `all` / `fill_gaps`) and
     * is what decides whether anything already aired is asked for — a show added as
     * `new_only` collects nothing until its next episode goes out.
     */
    suspend fun wantTvShow(detail: DiscoverDetail, autoWantMode: String = "new_only"): String {
        val body = buildJsonObject {
            put("title", detail.title)
            detail.year?.let { put("year", it) }
            detail.description?.let { put("description", it) }
            detail.posterUrl.takeIf { it.isNotEmpty() }?.let { put("poster_url", it) }
            detail.backdropUrl?.let { put("backdrop_url", it) }
            if (detail.identifiers.isNotEmpty()) {
                putJsonObject("identifiers") { detail.identifiers.forEach { (k, v) -> put(k, v) } }
            }
            if (detail.genres.isNotEmpty()) {
                putJsonArray("genres") { detail.genres.forEach { add(JsonPrimitive(it)) } }
            }
            detail.rating?.let { put("rating", it.toDouble()) }
            detail.network?.let { put("network", it) }
            detail.runtime?.let { put("runtime", it) }
        }
        val resp = decode<CreateEntityResponse>(post("/api/v1/admin/tv/shows", body.toString()))
        post(
            "/api/v1/admin/tv/shows/${resp.id}/monitor",
            """{"is_monitored":${autoWantMode != "none"},"auto_want_mode":"$autoWantMode"}""",
        )
        invalidateTvShows()
        return resp.id
    }

    /** Create a book from discover data as wanted. Returns the kobbler ID. */
    suspend fun wantBook(detail: DiscoverDetail): String {
        val body = buildJsonObject {
            put("title", detail.title)
            put("want", true)
            if (detail.authors.isNotEmpty()) {
                putJsonArray("authors") {
                    detail.authors.forEach { name ->
                        add(buildJsonObject { put("name", name) })
                    }
                }
            }
            detail.description?.let { put("description", it) }
            detail.posterUrl.takeIf { it.isNotEmpty() }?.let { put("cover_url", it) }
            if (detail.identifiers.isNotEmpty()) {
                putJsonObject("identifiers") { detail.identifiers.forEach { (k, v) -> put(k, v) } }
            }
            if (detail.genres.isNotEmpty()) {
                putJsonArray("genres") { detail.genres.forEach { add(JsonPrimitive(it)) } }
            }
            detail.rating?.let { put("rating", it.toDouble()) }
        }
        val resp = decode<CreateEntityResponse>(post("/api/v1/library/books", body.toString()))
        invalidateBooks()
        return resp.id
    }

    // -- Debrid streaming --

    /**
     * Per-server capability probe. Memoised for the lifetime of the instance: concurrent
     * callers share one in-flight pair of requests, and a success is never re-fetched.
     */
    suspend fun checkStreamingAvailability() {
        if (streamingProbed) return
        streamingProbeMutex.withLock {
            if (streamingProbed) return
            coroutineScope {
                val stats = async {
                    quiet<Boolean?>(null) { decode<LibraryStatsDto>(get("/api/v1/library/stats")).kobloadConfigured }
                }
                val config = async {
                    quiet<AdminConfigDto?>(null) { decode<AdminConfigDto>(get("/api/v1/admin/config")) }
                }
                val configured = stats.await()
                canStream = configured ?: false
                config.await()?.let {
                    castReceiverAppId = it.castReceiverAppId
                    dev.kobbler.app.platform.saveCastAppId(it.castReceiverAppId)
                }
                if (configured != null) streamingProbed = true
            }
        }
    }

    /** Search for debrid stream options for a movie or episode. */
    /** The title's release already on disk, if any — playable without a search or the debrid host. */
    suspend fun localStreamOption(mediaType: String, mediaId: String): StreamOption? = try {
        decode<StreamOptionDto>(
            get("/api/v1/admin/stream/local?media_type=$mediaType&media_id=${mediaId.encodeURLParameter()}")
        ).toModel()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    suspend fun streamSearch(
        movieId: String? = null,
        episodeId: String? = null,
        discover: DiscoverDetail? = null,
        discoverEpisode: DiscoverEpisodeRef? = null,
    ): StreamSearchResult {
        val body = buildJsonObject {
            movieId?.let { put("movie_id", it) }
            episodeId?.let { put("episode_id", it) }
            discover?.let { d ->
                put("title", d.title)
                d.year?.let { put("year", it) }
                put("media_type", d.mediaType)
                d.identifiers["imdb_id"]?.let { put("imdb_id", it) }
                d.identifiers["tmdb_id"]?.toIntOrNull()?.let { put("tmdb_id", it) }
            }
            discoverEpisode?.let {
                put("season", it.season)
                put("episode", it.episode)
            }
        }
        return decodeMap<StreamSearchResponseDto, StreamSearchResult>(post("/api/v1/admin/stream/search", body.toString())) { resp ->
            StreamSearchResult(
                options = applyPreferredOrder(
                    options = resp.options.map { it.toModel() },
                    preferredInfoHash = resp.preferredInfoHash,
                    preferredFileId = resp.preferredFileId,
                ),
                errors = resp.errors,
            )
        }
    }

    private fun applyPreferredOrder(
        options: List<StreamOption>,
        preferredInfoHash: String?,
        preferredFileId: Int?,
    ): List<StreamOption> {
        if (preferredInfoHash == null) return options
        val idx = options.indexOfFirst { o ->
            o.infoHash == preferredInfoHash && (preferredFileId == null || o.fileId == preferredFileId)
        }
        if (idx <= 0) return options
        val mutable = options.toMutableList()
        val preferred = mutable.removeAt(idx)
        mutable.add(0, preferred)
        return mutable
    }

    /** Resolve a stream option. Returns Ready (play immediately) or Caching (needs polling). */
    suspend fun streamResolve(
        option: StreamOption,
        mediaType: String,
        mediaId: String? = null,
        startTime: Int? = null,
        estimatedDuration: Int? = null,
        autoCreate: DiscoverDetail? = null,
        autoCreateEpisode: DiscoverEpisodeRef? = null,
        audioTitle: String? = null,
    ): StreamResolveResult {
        val target = StreamTarget(mediaType, mediaId, autoCreate, autoCreateEpisode, audioTitle)
        val body = buildJsonObject {
            put("debrid_provider_id", option.debridProviderId)
            option.infoHash?.let { put("info_hash", it) }
            option.fileId?.let { put("file_id", it) }
            put("release_title", option.releaseTitle)
            put("debrid_provider", option.debridProvider)
            option.qualityJson?.let { put("quality", it) }
            estimatedDuration?.let { put("estimated_duration", it) }
            putStreamTarget(target, startTime)
        }
        val respText = post("/api/v1/admin/stream/resolve", body.toString())
        val disc = decode<StreamResolveDiscriminatorDto>(respText)
        if (disc.sessionId != null) {
            // Cached / instant — returns a full playback session
            val session = decode<SessionResponse>(respText).toStreamSession()
                ?: error("No stream URL in resolve response")
            if (autoCreate != null && session.mediaId != null) invalidateAutoCreated()
            return StreamResolveResult.Ready(session)
        }
        val providerId = disc.debridProviderId ?: error("Missing debrid_provider_id")
        val torrentId = disc.torrentId ?: error("Missing torrent_id")
        return StreamResolveResult.Caching(providerId, torrentId, option)
    }

    private fun JsonObjectBuilder.putStreamTarget(target: StreamTarget, startTime: Int?) {
        put("media_type", target.mediaType)
        target.mediaId?.let { put("media_id", it) }
        startTime?.let { put("start_time", it) }
        TrackPrefs.current(target.audioTitle).putInto(this)
        put("capabilities", capabilitiesJson())
        target.discover?.let { ac ->
            putJsonObject("auto_create") {
                put("title", ac.title)
                ac.year?.let { put("year", it) }
                if (ac.identifiers.isNotEmpty()) {
                    putJsonObject("identifiers") { ac.identifiers.forEach { (k, v) -> put(k, v) } }
                }
                ac.description?.let { put("description", it) }
                ac.posterUrl.takeIf { it.isNotEmpty() }?.let { put("poster_url", it) }
                ac.backdropUrl?.let { put("backdrop_url", it) }
                if (ac.genres.isNotEmpty()) {
                    putJsonArray("genres") { ac.genres.forEach { add(JsonPrimitive(it)) } }
                }
                ac.rating?.let { put("rating", it.toDouble()) }
                ac.ratingCount?.let { put("rating_count", it) }
                ac.runtime?.let { put("runtime", it) }
                ac.network?.let { put("network", it) }
                target.episode?.let { ep ->
                    put("provider", ac.provider)
                    put("provider_id", ac.providerId)
                    put("season_number", ep.season)
                    put("episode_number", ep.episode)
                }
            }
        }
    }

    suspend fun prewarmStream(target: StreamTarget, startTime: Int? = null): Prewarm? {
        val body = buildJsonObject {
            putStreamTarget(target, startTime)
            target.episode?.let {
                put("season", it.season)
                put("episode", it.episode)
            }
        }
        val text = post("/api/v1/admin/stream/prewarm", body.toString())
        if (text.isBlank()) return null
        val dto = decode<PrewarmDto>(text)
        val id = dto.prewarmId ?: return null
        return Prewarm(id = id, ready = dto.state == "ready", option = dto.option?.toModel())
    }

    suspend fun claimPrewarm(prewarmId: String, autoCreated: Boolean): StreamSession {
        val session = decode<SessionResponse>(post("/api/v1/admin/stream/prewarm/$prewarmId/claim", "{}")).toStreamSession()
            ?: error("No stream URL in claimed prewarm")
        if (autoCreated && session.mediaId != null) invalidateAutoCreated()
        return session
    }

    /** False once the server no longer holds it. */
    suspend fun touchPrewarm(prewarmId: String): Boolean =
        quiet(false) { post("/api/v1/admin/stream/prewarm/$prewarmId/touch", "{}"); true }

    suspend fun releasePrewarm(prewarmId: String) {
        quiet(Unit) { delete("/api/v1/admin/stream/prewarm/$prewarmId") }
    }

    suspend fun keepStream(sessionId: String): KeepProgress =
        decode<KeepDto>(post("/api/v1/admin/stream/$sessionId/keep", "{}")).toModel()

    suspend fun keepStatus(sessionId: String): KeepProgress =
        decode<KeepDto>(get("/api/v1/admin/stream/$sessionId/keep")).toModel()

    /** A kept stream lands as a file on a library title the cached pages still show as streamable. */
    fun invalidateKept() {
        invalidateMovies()
        invalidateTvShows()
    }

    /** Poll debrid caching status. */
    suspend fun streamResolveStatus(providerId: String, torrentId: String): StreamCachingStatus {
        val resp = decode<StreamResolveStatusResponseDto>(
            get("/api/v1/admin/stream/resolve/status?provider_id=${providerId.encodeURLParameter()}&torrent_id=${torrentId.encodeURLParameter()}")
        )
        return StreamCachingStatus(status = resp.status, progress = resp.progress, message = resp.message)
    }

    /** Delete a debrid stream session. */
    suspend fun deleteDebridSession(sessionId: String) {
        quiet(Unit) { delete("/api/v1/admin/stream/$sessionId") }
    }

    /** Tell the server whether a debrid stream played, so the next search ranks by it. */
    suspend fun reportStreamOutcome(sessionId: String, ok: Boolean, reason: String? = null) {
        val body = buildJsonObject {
            put("ok", ok)
            reason?.let { put("reason", it.take(500)) }
        }
        quiet(Unit) { post("/api/v1/admin/stream/$sessionId/outcome", body.toString()) }
    }

    /** Fetch episodes for a discover TV show season. */
    suspend fun getDiscoverSeason(provider: String, providerId: String, seasonNumber: Int): List<DiscoverEpisode> {
        return decodeMap<DiscoverSeasonDetailDto, List<DiscoverEpisode>>(
            get("/api/v1/admin/discover/tv/$provider/$providerId/season/$seasonNumber")
        ) { resp ->
            resp.episodes.map { ep ->
                DiscoverEpisode(
                    episodeNumber = ep.episodeNumber,
                    title = ep.title,
                    airDate = ep.airDate,
                    runtime = ep.runtime,
                    stillUrl = ep.stillUrl?.let { resolveUrlOrNull(apiUrl, it) },
                    description = ep.description,
                )
            }
        }
    }

    /** Result of creating a streaming session — includes session ID, enrichment data. */
    @Serializable
    data class StreamSession(
        val url: String,
        val sessionId: String,
        val chapters: List<SessionChapter> = emptyList(),
        val skipSegments: List<SkipSegment> = emptyList(),
        val thumbnailsUrl: String? = null,
        val lufs: Double? = null,
        val audioTracks: List<AudioTrackDto> = emptyList(),
        val subtitles: List<PlaybackSubtitleTrackDto> = emptyList(),
        /** Audio stream index the server actually picked (matches one in audioTracks). */
        val selectedAudioIndex: Int? = null,
        /** Subtitle stream index the server actually picked (matches one in subtitles). */
        val selectedSubtitleIndex: Int? = null,
        /** Library media ID, set when auto-created via debrid streaming from discover. */
        val mediaId: String? = null,
        /**
         * Where the server rebased the timeline to, when it honoured a resume request.
         * Null on direct play, where the whole file is served and the client seeks.
         */
        val serverStartSeconds: Int? = null,
        /** Source video dimensions — used to bucket the aspect-ratio preference. */
        val videoWidth: Int? = null,
        val videoHeight: Int? = null,
        val picture: PictureArea? = null,
        /**
         * The library file behind this session, when there is one. The server does not
         * return it, but the caller looked it up to create the session — and it is what
         * `skip_segments_ready` is addressed to. Null for debrid streams.
         */
        val fileId: String? = null,
        /**
         * How the server will deliver this — `direct_play`, `remux`, `audio_transcode`,
         * `video_transcode`. What a start is allowed to cost turns on it: a direct-played
         * local file has nothing to prepare, so a second of black is already suspicious.
         */
        val method: String? = null,
        val durationSeconds: Double = 0.0,
    )

    /**
     * Get a streaming session for a movie. Returns URL + session ID, or null.
     *
     * One round trip: the server resolves the media to its preferred available file. The
     * client used to list the files first and hand back an id, which put a whole request
     * between the tap and the first byte to re-derive a choice the server owns anyway.
     * A media with no available file comes back 404, which is the null.
     */
    suspend fun getMovieStreamSession(movieId: String, startTimeSec: Int? = null, audioTitle: String? = null): StreamSession? =
        offline?.playbackSession("movie", movieId) { createDownloadSession("movie", movieId) }
            ?: createDirectSession("movie", movieId, startTimeSec, audioTitle)

    /** Get a streaming session for an episode. See [getMovieStreamSession]. */
    suspend fun getEpisodeStreamSession(episodeId: String, startTimeSec: Int? = null, audioTitle: String? = null): StreamSession? =
        offline?.playbackSession("episode", episodeId) { createDownloadSession("episode", episodeId) }
            ?: createDirectSession("episode", episodeId, startTimeSec, audioTitle)

    /**
     * A session onto the title's own file, whatever the quality setting says: a download is
     * the original, and its bytes have to be the same ones on every session it is resumed with.
     */
    internal suspend fun createDownloadSession(mediaType: String, mediaId: String): StreamSession? {
        val prefs = TrackPrefs.current()
        val reqBody = json.encodeToString(CreateSessionRequest.serializer(), CreateSessionRequest(
            mediaType = mediaType,
            mediaId = mediaId,
            capabilities = capabilities(),
            preferredAudioLanguage = prefs.audioLanguage,
            preferredSubtitleLanguage = prefs.subtitleLanguage,
            subtitlesEnabled = prefs.subtitlesEnabled,
        ))
        val respJson = try {
            post("/api/v1/admin/playback/sessions", reqBody)
        } catch (e: ApiException) {
            if (e.statusCode == 404) return null else throw e
        }
        return decode<SessionResponse>(respJson).toStreamSession()
    }

    /** Decode a `watch_progress` event, or null for one in an unexpected shape. */
    fun decodeWatchProgress(data: JsonObject): WatchProgress? =
        runCatching { json.decodeFromJsonElement(WatchProgress.serializer(), data) }.getOrNull()

    /** Decode the `segments` payload carried by a `skip_segments_ready` event. */
    fun decodeSkipSegments(element: kotlinx.serialization.json.JsonElement): List<SkipSegment> =
        json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(SkipSegment.serializer()), element)

    /** Decode the `subtitles` payload carried by a debrid `probe_complete` event. */
    fun decodeSubtitleTracks(element: kotlinx.serialization.json.JsonElement): List<PlaybackSubtitleTrackDto> =
        json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(PlaybackSubtitleTrackDto.serializer()), element)

    /**
     * The server's live event stream, one connection shared by every collector; it closes
     * a few seconds after the last one leaves, or as soon as the app is backgrounded. See
     * [SharedEventStream].
     */
    fun libraryEvents(): kotlinx.coroutines.flow.Flow<LibraryEventFrame> = sharedLibraryEvents.events

    private val sharedLibraryEvents by lazy {
        SharedEventStream(
            upstream = libraryEventStream(eventClient, apiUrl, token, json),
            foreground = AppForeground.state,
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default),
        )
    }

    /**
     * Events for one debrid playback session. Cold: collecting connects, and the connection
     * closes with the collector's scope.
     *
     * Separate from the library stream because a debrid stream has no library file for
     * events to be addressed to — the session is the only handle there is.
     */
    fun debridSessionEvents(sessionId: String): kotlinx.coroutines.flow.Flow<LibraryEventFrame> =
        debridSessionEventStream(eventClient, apiUrl, sessionId, token, json)

    /**
     * Has the server dropped this session?
     *
     * An API restart drops every session it was holding in memory, and a direct-play
     * stream that has lost its session makes no request to fail until the viewer presses
     * play — so asking is the only way to find out while a player is idle. Only a 404
     * says so: a request that never arrived is the network, not the session.
     */
    suspend fun sessionGone(sessionId: String): Boolean = offline?.isLocalSession(sessionId) != true && quiet(false) {
        httpClient.get("$apiUrl/api/v1/admin/playback/sessions/$sessionId/heartbeat") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.status == HttpStatusCode.NotFound
    }

    /**
     * What the server knows about whether a session can still deliver bytes.
     *
     * [SessionStatus.Missing] is the server having no record of it at all, which is a
     * different thing from not being able to ask — a request that fails on the way out
     * says nothing about the session and must not be read as one that died.
     */
    suspend fun sessionStatus(sessionId: String): SessionStatus {
        if (offline?.isLocalSession(sessionId) == true) return SessionStatus.Unknown
        val resp = try {
            httpClient.get("$apiUrl/api/v1/admin/playback/sessions/$sessionId/status") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return SessionStatus.Unknown
        }
        if (resp.status == HttpStatusCode.NotFound) return SessionStatus.Missing
        if (!resp.status.isSuccess()) return SessionStatus.Unknown
        return quiet(SessionStatus.Unknown) { decode<SessionStatusDto>(resp.bodyAsText()) }
    }

    @Serializable
    data class SeekResult(
        @SerialName("seek_generation") val generation: Int,
        @SerialName("start_time") val startTimeSeconds: Double,
    )

    /** Restart a transcode at [seconds] of the title; the server rebuilds its playlist from there. */
    suspend fun seekSession(sessionId: String, seconds: Double): SeekResult? = quiet(null) {
        decode<SeekResult>(
            post(
                "/api/v1/admin/playback/sessions/$sessionId/seek",
                buildJsonObject { put("time_seconds", seconds) }.toString(),
            ),
        )
    }

    /** Close a streaming session, cleaning up server-side resources. */
    suspend fun closeSession(sessionId: String) {
        if (offline?.isLocalSession(sessionId) == true) return
        quiet(Unit) {
            httpClient.delete("$apiUrl/api/v1/admin/playback/sessions/$sessionId") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
        }
    }

    suspend fun getMovieStreamUrl(movieId: String): String? = getMovieStreamSession(movieId)?.url
    suspend fun getEpisodeStreamUrl(episodeId: String): String? = getEpisodeStreamSession(episodeId)?.url

    /** A session for another device to play, built for what that device can decode. */
    suspend fun createCastSession(
        mediaType: String,
        mediaId: String,
        capabilities: ClientCapabilities,
        startTimeSec: Int,
    ): StreamSession? = createDirectSession(mediaType, mediaId, startTimeSec, capabilities = capabilities)

    private suspend fun createDirectSession(
        mediaType: String,
        mediaId: String,
        startTimeSec: Int? = null,
        audioTitle: String? = null,
        capabilities: ClientCapabilities = capabilities(),
    ): StreamSession? {
        val qualityPref = UserSettings.getString(KEY_STREAMING_QUALITY) ?: "auto"
        val forceTranscode = qualityPref != "auto"
        val maxBitrate = when (qualityPref) {
            "1080" -> 8000
            "720" -> 4000
            "480" -> 2000
            else -> null
        }
        val prefs = TrackPrefs.current(audioTitle)
        val reqBody = json.encodeToString(CreateSessionRequest.serializer(), CreateSessionRequest(
            mediaType = mediaType,
            mediaId = mediaId,
            capabilities = capabilities,
            forceTranscode = forceTranscode,
            maxBitrateKbps = maxBitrate,
            preferredAudioLanguage = prefs.audioLanguage,
            preferredAudioTitle = prefs.audioTitle,
            preferredSubtitleLanguage = prefs.subtitleLanguage,
            subtitlesEnabled = prefs.subtitlesEnabled,
            startTime = startTimeSec,
        ))
        // 404 is "this media has no file we can play", which is the caller's cue to go
        // looking for a stream rather than an error to surface. Every other status still
        // throws — a server that is down must not read as a library gap.
        val respJson = try {
            post("/api/v1/admin/playback/sessions", reqBody)
        } catch (e: ApiException) {
            if (e.statusCode == 404) return null else throw e
        }
        return decode<SessionResponse>(respJson).toStreamSession()
    }

    /** Asks the server for a copy of the title's file at [quality], or the one it already has. Null when it has no file. */
    internal suspend fun createOfflineExport(mediaType: String, mediaId: String, quality: String, audioTitle: String? = null): OfflineExport? {
        val body = buildJsonObject {
            put("media_type", mediaType)
            put("media_id", mediaId)
            put("quality", quality)
            put("preferred_audio_language", TrackPrefs.current().audioLanguage?.let { JsonPrimitive(it) } ?: JsonNull)
            audioTitle?.let { put("preferred_audio_title", it) }
        }
        val text = try {
            post("/api/v1/admin/offline/exports", body.toString())
        } catch (e: ApiException) {
            if (e.statusCode == 404) return null else throw e
        }
        return decode<OfflineExport>(text).resolved()
    }

    /** Null once the server no longer has it. */
    internal suspend fun offlineExport(exportId: String): OfflineExport? = try {
        decode<OfflineExport>(get("/api/v1/admin/offline/exports/$exportId")).resolved()
    } catch (e: ApiException) {
        if (e.statusCode == 404) null else throw e
    }

    private fun OfflineExport.resolved() = copy(fileUrl = resolveUrl(apiUrl, fileUrl))

    /** Switch audio track for a playback session. Returns updated session. */
    suspend fun switchAudioTrack(
        sessionId: String,
        audioStreamIndex: Int,
        resumeTime: Int,
        fileId: String? = null,
    ): StreamSession? {
        val body = buildJsonObject {
            put("audio_stream_index", audioStreamIndex)
            put("resume_time", resumeTime)
        }
        val respJson = post("/api/v1/admin/playback/sessions/$sessionId/switch-audio", body.toString())
        // Switching audio does not change which file is playing, and a null file id here
        // would silently stop skip segments arriving after load().
        return decode<SessionResponse>(respJson).toStreamSession(fileId = fileId)
    }

    private fun SessionResponse.toStreamSession(fileId: String? = this.fileId): StreamSession? {
        val url = directUrl ?: playlistUrl ?: return null
        return StreamSession(
            url = resolveUrl(apiUrl, url),
            sessionId = sessionId,
            chapters = chapters,
            skipSegments = skipSegments,
            thumbnailsUrl = thumbnailsUrl?.let { resolveUrl(apiUrl, it) },
            lufs = lufs,
            audioTracks = audioTracks,
            subtitles = subtitles.map { it.copy(url = resolveUrlOrNull(apiUrl, it.url)) },
            selectedAudioIndex = preferredAudioStreamIndex ?: selectedAudioIndex,
            selectedSubtitleIndex = selectedSubtitleIndex,
            mediaId = mediaId,
            serverStartSeconds = rebaseSeconds(),
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            picture = picture,
            fileId = fileId,
            method = method,
            durationSeconds = durationSeconds,
        )
    }

    suspend fun checkThumbnailsReady(sessionId: String): ThumbnailsProbe = quiet<ThumbnailsProbe>(ThumbnailsProbe.Pending) {
        val url = "/api/v1/admin/playback/sessions/$sessionId/thumbnails.vtt"
        val resp = httpClient.head("$apiUrl$url") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        when {
            resp.status.isSuccess() -> ThumbnailsProbe.Ready(resolveUrl(apiUrl, url))
            resp.status == HttpStatusCode.Gone -> ThumbnailsProbe.Gone
            else -> ThumbnailsProbe.Pending
        }
    }

    // -- Notifications --

    suspend fun getWatchHistory(source: String? = null, limit: Int = 100, offset: Int = 0): WatchHistoryPage {
        val path = buildString {
            append("/api/v1/admin/watch-history?limit=$limit&offset=$offset")
            if (source != null) append("&source=${source.encodeURLParameter()}")
        }
        return decodeMap<WatchHistoryResponseDto, WatchHistoryPage>(get(path)) { resp ->
            WatchHistoryPage(
                items = resp.items.map {
                    WatchHistoryEntry(
                        mediaType = it.mediaType,
                        tmdbId = it.tmdbId,
                        title = it.title,
                        year = it.year,
                        posterUrl = resolveUrlOrNull(apiUrl, it.posterUrl),
                        lastWatchedAt = it.lastWatchedAt,
                        sources = it.sources,
                        excluded = it.excluded,
                    )
                },
                total = resp.total,
            )
        }
    }

    suspend fun setWatchHistoryExcluded(entries: List<WatchHistoryEntry>, excluded: Boolean) {
        post("/api/v1/admin/watch-history/exclude", watchHistoryBody(entries) { put("excluded", excluded) })
        DataCache.invalidate("home")
    }

    suspend fun removeWatchHistory(entries: List<WatchHistoryEntry>) {
        post("/api/v1/admin/watch-history/remove", watchHistoryBody(entries) {})
        DataCache.invalidateWatchProgress()
    }

    private fun watchHistoryBody(entries: List<WatchHistoryEntry>, extra: JsonObjectBuilder.() -> Unit) = buildJsonObject {
        putJsonArray("items") {
            entries.filter { it.tmdbId != null }.forEach { e ->
                add(buildJsonObject { put("media_type", e.mediaType); put("tmdb_id", e.tmdbId) })
            }
        }
        extra()
    }.toString()

    suspend fun getNotifications(limit: Int = 20, offset: Int = 0, unreadOnly: Boolean = false): NotificationListResponse =
        decode<NotificationListResponse>(
            get("/api/v1/admin/notifications/inbox?limit=$limit&offset=$offset&unread_only=$unreadOnly")
        )

    suspend fun getUnreadNotificationCount(): Int =
        decode<UnreadCountResponse>(get("/api/v1/admin/notifications/inbox/unread-count")).count

    suspend fun markNotificationRead(id: String) {
        patch("/api/v1/admin/notifications/inbox/$id/read", "{}")
    }

    suspend fun markAllNotificationsRead() {
        post("/api/v1/admin/notifications/inbox/mark-all-read", "{}")
    }

    // -- Downloads --

    @Serializable
    data class DownloadsResponse(
        val items: List<kotlinx.serialization.json.JsonObject> = emptyList(),
        @SerialName("next_cursor") val nextCursor: String? = null,
    )

    suspend fun getDownloads(status: String? = null, cursor: String? = null, limit: Int = 25): DownloadsResponse {
        val path = buildString {
            append("/api/v1/admin/downloads?limit=$limit")
            if (status != null) append("&status=$status")
            if (cursor != null) append("&cursor=$cursor")
        }
        return decode<DownloadsResponse>(get(path))
    }

    suspend fun getRecentStreams(): List<RecentStreamDto> =
        decode<List<RecentStreamDto>>(get("/api/v1/admin/stream/recent"))

    // -- Subtitle Search --

    suspend fun searchMovieSubtitles(movieId: String, languages: List<String>): List<SubtitleSearchResultDto> =
        decode<List<SubtitleSearchResultDto>>(
            post("/api/v1/admin/movies/$movieId/subtitles/search", json.encodeToString(mapOf("languages" to languages)))
        )

    suspend fun searchEpisodeSubtitles(episodeId: String, languages: List<String>): List<SubtitleSearchResultDto> =
        decode<List<SubtitleSearchResultDto>>(
            post("/api/v1/admin/tv/episodes/$episodeId/subtitles/search", json.encodeToString(mapOf("languages" to languages)))
        )

    suspend fun downloadSubtitleToSession(
        sessionId: String,
        provider: String,
        subtitleId: String,
        language: String? = null,
    ): PlaybackSubtitleTrackDto {
        val dto = decode<PlaybackSubtitleTrackDto>(
            post(
                "/api/v1/admin/playback/sessions/$sessionId/subtitles",
                buildJsonObject {
                    put("provider", provider)
                    put("subtitle_id", subtitleId)
                    if (language != null) put("language", language)
                }.toString(),
            )
        )
        return dto.copy(url = resolveUrlOrNull(apiUrl, dto.url))
    }
}

data class DashboardData(
    val readNextItems: List<ContinueItem> = emptyList(),
    val movies: List<Movie>,
    val shows: List<TvShow>,
    val books: List<Book>,
    val ebooks: List<Book> = emptyList(),
    val audiobooks: List<Book> = emptyList(),
    val sports: List<TvShow> = emptyList(),
)
