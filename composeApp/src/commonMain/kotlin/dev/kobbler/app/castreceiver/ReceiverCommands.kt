package dev.kobbler.app.castreceiver

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed interface ReceiverCommand {
    data class SubtitleChange(val trackId: Int?) : ReceiverCommand
    data class AudioSwitch(val streamIndex: Int) : ReceiverCommand
    data class TokenRefresh(val token: String) : ReceiverCommand
    data object StatusRequest : ReceiverCommand
    data object CapabilitiesRequest : ReceiverCommand
    data object TracksRequest : ReceiverCommand
    data class Unsupported(val type: String) : ReceiverCommand
}

private val UnsupportedTypes = setOf("INIT_AUDIOBOOK", "INIT_TTS", "TTS_STOP", "AUDIOBOOK_SEEK", "AUDIOBOOK_RATE")

/**
 * A message off the custom namespace. `INIT_SESSION` reads as null: the LOAD carries the
 * same payload, and it is the LOAD that opens the title.
 */
fun parseReceiverCommand(json: String): ReceiverCommand? = runCatching {
    val root = Json.parseToJsonElement(json).jsonObject
    val payload = root["payload"] as? JsonObject
    fun int(key: String) = payload?.get(key)?.jsonPrimitive?.intOrNull
    when (val type = root["type"]?.jsonPrimitive?.contentOrNull) {
        "SUBTITLE_CHANGE" -> {
            val id = payload?.get("trackId") ?: return null
            ReceiverCommand.SubtitleChange(if (id is JsonNull) null else id.jsonPrimitive.intOrNull ?: return null)
        }
        "AUDIO_SWITCH" -> ReceiverCommand.AudioSwitch(int("streamIndex") ?: return null)
        "TOKEN_REFRESH" -> ReceiverCommand.TokenRefresh(payload?.get("token")?.jsonPrimitive?.contentOrNull ?: return null)
        "STATUS_REQUEST" -> ReceiverCommand.StatusRequest
        "CAPABILITIES_REQUEST" -> ReceiverCommand.CapabilitiesRequest
        "TRACKS_REQUEST" -> ReceiverCommand.TracksRequest
        in UnsupportedTypes -> ReceiverCommand.Unsupported(type!!)
        else -> null
    }
}.getOrNull()
