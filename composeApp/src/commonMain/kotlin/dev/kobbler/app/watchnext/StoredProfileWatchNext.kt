package dev.kobbler.app.watchnext

import dev.kobbler.app.data.KeyValueStore
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.PlatformKeyValueStore
import dev.kobbler.app.data.SharedHttp
import dev.kobbler.app.data.storedActiveProfile

/**
 * What the row should hold for the profile last signed in, read without a UI; empty when signed
 * out, or when a PIN guards the profile — the home screen is shown to whoever is in the room.
 */
suspend fun watchNextForStoredProfile(store: KeyValueStore = PlatformKeyValueStore): List<WatchNextProgramSpec> {
    val profile = storedActiveProfile(store)?.takeUnless { it.hasPin } ?: return emptyList()
    val items = SharedHttp.apiClient().use { KobblerApi(it, profile.apiUrl, profile.token).getWatchNext() }
    return watchNextPrograms(items, profile.id, profile.apiUrl)
}
