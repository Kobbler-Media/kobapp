package dev.kobbler.app.data

import dev.kobbler.app.platform.Settings

interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}

object PlatformKeyValueStore : KeyValueStore {
    override fun getString(key: String): String? = Settings.getString(key)
    override fun putString(key: String, value: String) = Settings.putString(key, value)
    override fun remove(key: String) = Settings.remove(key)
}

class ProfileSettings(
    private val store: KeyValueStore = PlatformKeyValueStore,
    private val profileIdProvider: () -> String?,
) {
    private fun scoped(key: String): String =
        profileIdProvider()?.let { "u_${it}__$key" } ?: key

    fun getString(key: String): String? = store.getString(scoped(key))
    fun putString(key: String, value: String) = store.putString(scoped(key), value)
    fun remove(key: String) = store.remove(scoped(key))

    fun migrateLegacyKeys(keys: List<String>, profileId: String) {
        for (key in keys) {
            val legacy = store.getString(key) ?: continue
            store.putString("u_${profileId}__$key", legacy)
            store.remove(key)
        }
    }
}

/**
 * Process-wide accessor for per-profile key-value state. Bound by [AuthState]
 * on startup so call sites that don't have an AuthState in scope (repositories,
 * API helpers) still read/write against the active profile's namespace.
 */
object UserSettings {
    private var inner: ProfileSettings = ProfileSettings { null }

    fun bind(profileIdProvider: () -> String?) {
        inner = ProfileSettings(profileIdProvider = profileIdProvider)
    }

    fun getString(key: String): String? = inner.getString(key)
    fun putString(key: String, value: String) = inner.putString(key, value)
    fun remove(key: String) = inner.remove(key)
    fun migrateLegacyKeys(keys: List<String>, profileId: String) =
        inner.migrateLegacyKeys(keys, profileId)
}
