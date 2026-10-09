package dev.kobbler.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.random.Random
import kotlin.time.Instant

/**
 * Who this player is, for cross-device handoff (server: `api/src/watch_handoff.rs`).
 *
 * It only has to tell this process's own position saves apart from another device's
 * while it runs, so it is minted per launch and never stored. A player also ignores
 * saves older than when it opened, which keeps a relaunch from mistaking its previous
 * self for another device.
 */
object PlaybackClient {
    val id: String = buildString {
        append("app-")
        repeat(16) { append("0123456789abcdef"[Random.nextInt(16)]) }
    }
}

/** Enough about an episode to name it on the prompt. */
@Serializable
data class HandoffEpisode(
    @SerialName("show_title") val showTitle: String = "",
    val title: String? = null,
    @SerialName("season_identifier") val seasonIdentifier: String = "",
    @SerialName("episode_identifier") val episodeIdentifier: String = "",
    @SerialName("still_url") val stillUrl: String? = null,
)

/** A position one of this user's devices saved: the `watch_progress` event. */
@Serializable
data class WatchProgress(
    @SerialName("media_type") val mediaType: String,
    @SerialName("media_id") val mediaId: String,
    /** The show for an episode, the movie itself for a movie. */
    @SerialName("scope_id") val scopeId: String,
    @SerialName("position_seconds") val positionSeconds: Int,
    val watched: Boolean = false,
    @SerialName("client_id") val clientId: String,
    @SerialName("client_label") val clientLabel: String? = null,
    @SerialName("saved_at") val savedAt: String,
    val episode: HandoffEpisode? = null,
    @SerialName("movie_title") val movieTitle: String? = null,
) {
    val savedAtInstant: Instant? get() = runCatching { Instant.parse(savedAt) }.getOrNull()
}

@Serializable
data class PlaybackHandoffResponse(
    @SerialName("scope_id") val scopeId: String? = null,
    val latest: WatchProgress? = null,
)
