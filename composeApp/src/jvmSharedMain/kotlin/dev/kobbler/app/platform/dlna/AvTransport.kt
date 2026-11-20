package dev.kobbler.app.platform.dlna

import io.ktor.client.HttpClient

internal enum class TransportState { Playing, Paused, Stopped, Transitioning, NoMedia, Other }

internal data class TransportPosition(val positionSec: Double, val durationSec: Double)

/** The AVTransport of one renderer, instance 0 — the only one most of them have. */
internal class AvTransport(private val http: HttpClient, private val endpoint: ServiceEndpoint) {
    private val instance = "InstanceID" to "0"

    suspend fun setUri(uri: String, metadata: String) =
        http.soap(endpoint, "SetAVTransportURI", listOf(instance, "CurrentURI" to uri, "CurrentURIMetaData" to metadata))

    suspend fun play() = http.soap(endpoint, "Play", listOf(instance, "Speed" to "1"))

    suspend fun pause() = http.soap(endpoint, "Pause", listOf(instance))

    suspend fun stop() = http.soap(endpoint, "Stop", listOf(instance))

    suspend fun seek(positionSec: Int) =
        http.soap(endpoint, "Seek", listOf(instance, "Unit" to "REL_TIME", "Target" to upnpTime(positionSec)))

    suspend fun position(): TransportPosition {
        val out = http.soap(endpoint, "GetPositionInfo", listOf(instance))
        return TransportPosition(
            positionSec = parseUpnpTime(out["RelTime"]) ?: 0.0,
            durationSec = parseUpnpTime(out["TrackDuration"]) ?: 0.0,
        )
    }

    suspend fun state(): TransportState =
        when (http.soap(endpoint, "GetTransportInfo", listOf(instance))["CurrentTransportState"]) {
            "PLAYING" -> TransportState.Playing
            "PAUSED_PLAYBACK", "PAUSED_RECORDING" -> TransportState.Paused
            "STOPPED" -> TransportState.Stopped
            "TRANSITIONING" -> TransportState.Transitioning
            "NO_MEDIA_PRESENT" -> TransportState.NoMedia
            else -> TransportState.Other
        }
}

internal fun upnpTime(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    return "${s / 3600}:${(s / 60 % 60).toString().padStart(2, '0')}:${(s % 60).toString().padStart(2, '0')}"
}

/** `H+:MM:SS[.F+]`, or null for `NOT_IMPLEMENTED` and anything else unreadable. */
internal fun parseUpnpTime(value: String?): Double? {
    val parts = value?.trim()?.split(':')?.takeIf { it.size == 3 } ?: return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    val s = parts[2].substringBefore('/').toDoubleOrNull() ?: return null
    return h * 3600.0 + m * 60 + s
}
