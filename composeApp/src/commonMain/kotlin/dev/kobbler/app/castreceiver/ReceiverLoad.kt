package dev.kobbler.app.castreceiver

import dev.kobbler.app.data.CastInitSession
import dev.kobbler.app.ui.player.CastableTypes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

data class ReceiverTitle(
    val mediaType: String,
    val mediaId: String,
    val title: String,
    val subtitle: String?,
    val posterUrl: String?,
)

data class ReceiverLoad(val session: CastInitSession, val positionSec: Int) {
    val title: ReceiverTitle
        get() = ReceiverTitle(
            mediaType = session.mediaType,
            mediaId = checkNotNull(session.mediaId),
            title = session.metadata.title,
            subtitle = session.metadata.subtitle,
            posterUrl = session.metadata.posterUrl,
        )
}

private val loadJson = Json { ignoreUnknownKeys = true }

/**
 * Read a Cast LOAD's `customData`. [startSec] is the load's own start, which the phone
 * gives relative to where its transcode began.
 */
fun parseReceiverLoad(customDataJson: String?, startSec: Double): ReceiverLoad? = runCatching {
    val custom = loadJson.parseToJsonElement(customDataJson ?: return null).jsonObject
    val payload = custom["kobblerSession"] as? JsonObject ?: return null
    val session = loadJson.decodeFromJsonElement(CastInitSession.serializer(), payload)
    if (session.mediaId == null || session.mediaType !in CastableTypes) return null
    ReceiverLoad(session, (session.startOffset + startSec.coerceAtLeast(0.0)).toInt())
}.getOrNull()
