package dev.kobbler.app.castreceiver

import dev.kobbler.app.data.CastSkip
import dev.kobbler.app.data.CastTracks
import dev.kobbler.app.data.ClientCapabilities
import dev.kobbler.app.platform.CastFeature
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

private val replyJson = Json { encodeDefaults = true; explicitNulls = true }

object ReceiverReplies {
    fun position(now: ReceiverNow) = message("POSITION_UPDATE", clock(now))

    fun status(now: ReceiverNow) = message(
        "STATUS_RESPONSE",
        buildJsonObject {
            clock(now).forEach { (k, v) -> put(k, v) }
            put("volume", 1)
            put("mediaId", now.mediaId)
            put("mediaType", now.mediaType)
            put(
                "metadata",
                buildJsonObject {
                    put("title", now.title)
                    put("subtitle", now.subtitle)
                    put("posterUrl", now.posterUrl)
                },
            )
            put("isAudiobook", false)
            put("isTts", false)
        },
    )

    fun capabilities(capabilities: ClientCapabilities, features: Set<CastFeature>) = message(
        "CAPABILITIES",
        buildJsonObject {
            replyJson.encodeToJsonElement(capabilities).jsonObject.forEach { (k, v) -> put(k, v) }
            put("features", JsonArray(features.map { JsonPrimitive(it.wire) }))
        },
    )

    fun refused(type: String) = message("UNSUPPORTED", buildJsonObject { put("type", type) })

    fun tracks(tracks: CastTracks) = message("TRACKS", replyJson.encodeToJsonElement(tracks))

    fun episodeChanged(next: NextEpisode) = message(
        "EPISODE_CHANGED",
        buildJsonObject {
            put("episodeId", next.episodeId)
            put("title", next.title)
            put("seasonIdentifier", next.seasonIdentifier)
            put("episodeIdentifier", next.episodeIdentifier)
            put("showTitle", next.showTitle)
        },
    )

    fun skip(skip: CastSkip?) = message("SKIP_SEGMENT", skip?.let { replyJson.encodeToJsonElement(it) } ?: JsonNull)

    val finished = message("MEDIA_FINISHED")
    val expired = message("SESSION_EXPIRED")

    private fun clock(now: ReceiverNow) = buildJsonObject {
        put("currentTime", now.positionMs / 1000.0)
        put("duration", now.durationMs / 1000.0)
        put("playing", now.playing)
    }

    private fun message(type: String, payload: JsonElement? = null) = buildJsonObject {
        put("type", type)
        if (payload != null) put("payload", payload)
    }.toString()
}
