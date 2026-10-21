package dev.kobbler.app.data

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Immutable
data class StreamTarget(
    val mediaType: String,
    val mediaId: String? = null,
    val discover: DiscoverDetail? = null,
    val episode: DiscoverEpisodeRef? = null,
) {
    val key: String
        get() = mediaId?.let { "$mediaType:$it" }
            ?: "discover:${discover?.provider}:${discover?.providerId}:${episode?.season}:${episode?.episode}"

    companion object {
        fun movie(id: String) = StreamTarget("movie", mediaId = id)
        fun episode(id: String) = StreamTarget("episode", mediaId = id)
        fun discover(detail: DiscoverDetail, episode: DiscoverEpisodeRef?) =
            StreamTarget(detail.mediaType, discover = detail, episode = episode)
    }
}

@Immutable
data class Prewarm(val id: String, val ready: Boolean, val option: StreamOption?)

@Serializable
internal data class PrewarmDto(
    val state: String = "",
    @SerialName("prewarm_id") val prewarmId: String? = null,
    val option: StreamOptionDto? = null,
)

enum class KeepState { Queued, Downloading, Importing, Done, Failed }

@Immutable
data class KeepProgress(val state: KeepState, val progress: Float? = null)

@Serializable
internal data class KeepDto(
    @SerialName("job_id") val jobId: String? = null,
    val state: String = "",
    val progress: Double? = null,
)

internal fun KeepDto.toModel() = KeepProgress(
    state = when (state) {
        "queued" -> KeepState.Queued
        "downloading" -> KeepState.Downloading
        "importing" -> KeepState.Importing
        "done" -> KeepState.Done
        else -> KeepState.Failed
    },
    // Either a fraction or a percentage; the contract does not pin which.
    progress = progress?.let { if (it > 1.0) it / 100.0 else it }?.toFloat()?.coerceIn(0f, 1f),
)
