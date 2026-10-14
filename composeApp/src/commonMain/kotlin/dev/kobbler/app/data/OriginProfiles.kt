package dev.kobbler.app.data

import dev.kobbler.app.platform.Settings
import kotlinx.serialization.json.Json

/** [OriginProfile]s by host, kept across launches. */
class OriginProfiles(
    private val read: (String) -> String?,
    private val write: (String, String) -> Unit,
) {
    fun get(host: String): OriginProfile =
        read(key(host))?.let { runCatching { json.decodeFromString<OriginProfile>(it) }.getOrNull() }
            ?: OriginProfile()

    fun update(host: String, change: (OriginProfile) -> OriginProfile) {
        val current = get(host)
        val next = change(current)
        if (next != current) write(key(host), json.encodeToString(next))
    }

    private fun key(host: String) = "origin_profile.$host"

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        val Persistent = OriginProfiles(read = Settings::getString, write = Settings::putString)
    }
}
