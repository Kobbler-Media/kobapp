package dev.kobbler.app.data

import dev.kobbler.app.ui.dashboard.KEY_DEFAULT_AUDIO_LANG
import dev.kobbler.app.ui.dashboard.KEY_DEFAULT_SUBTITLE_LANG
import dev.kobbler.app.ui.dashboard.KEY_SUBTITLES_ENABLED
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put

internal data class TrackPrefs(
    val audioLanguage: String?,
    val audioTitle: String?,
    val subtitleLanguage: String?,
    val subtitlesEnabled: Boolean,
) {
    fun putInto(body: JsonObjectBuilder) {
        audioLanguage?.let { body.put("preferred_audio_language", it) }
        audioTitle?.let { body.put("preferred_audio_title", it) }
        subtitleLanguage?.let { body.put("preferred_subtitle_language", it) }
        body.put("subtitles_enabled", subtitlesEnabled)
    }

    companion object {
        fun current(audioTitle: String? = null) = TrackPrefs(
            audioLanguage = UserSettings.getString(KEY_DEFAULT_AUDIO_LANG)?.takeIf { it.isNotBlank() },
            audioTitle = audioTitle,
            subtitleLanguage = UserSettings.getString(KEY_DEFAULT_SUBTITLE_LANG)?.takeIf { it.isNotBlank() },
            subtitlesEnabled = UserSettings.getString(KEY_SUBTITLES_ENABLED) != "false",
        )
    }
}
