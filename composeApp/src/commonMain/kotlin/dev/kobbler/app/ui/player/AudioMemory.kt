package dev.kobbler.app.ui.player

import dev.kobbler.app.data.DiscoverDetail
import dev.kobbler.app.data.DiscoverEpisodeRef
import dev.kobbler.app.data.UserSettings

private const val KEY_PREFIX = "audio_title:"

// By title, not index: the server finds it again in the next episode's file, where the
// stream indices need not line up.
internal fun rememberAudioChoice(titleKey: String?, trackTitle: String?) {
    val key = KEY_PREFIX + (titleKey ?: return)
    trackTitle?.takeIf { it.isNotBlank() }?.let { UserSettings.putString(key, it) } ?: UserSettings.remove(key)
}

internal fun rememberedAudioTitle(titleKey: String?): String? =
    titleKey?.let { UserSettings.getString(KEY_PREFIX + it) }

internal fun rememberedAudioTitle(mediaType: String, mediaId: String?, showId: String?): String? =
    rememberedAudioTitle(playbackTitleKey(mediaType, mediaId, showId))

internal fun DiscoverDetail.rememberedAudioTitle(episode: DiscoverEpisodeRef?): String? =
    libraryId?.let { rememberedAudioTitle(if (episode == null) "movie" else "episode", it, it) }
