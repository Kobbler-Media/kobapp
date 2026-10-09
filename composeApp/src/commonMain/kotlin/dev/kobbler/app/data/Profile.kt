package dev.kobbler.app.data

import kotlinx.serialization.Serializable

@Serializable
data class Profile(
    val id: String,
    val userId: String? = null,
    val email: String? = null,
    val token: String,
    val apiUrl: String,
    val role: String = "viewer",
    val avatarUrl: String? = null,
    val hasPin: Boolean = false,
    /** The name set on the server. Null means nobody has chosen one. */
    val name: String? = null,
    /**
     * The server rejected this profile's token. The profile is kept so relinking can reuse
     * its id - per-profile prefs are namespaced by it and would be lost by a fresh add.
     */
    val needsRelink: Boolean = false,
) {
    /** The chosen name, or the best guess the email allows until someone sets one. */
    val displayName: String
        get() = name?.trim()?.ifEmpty { null }
            ?: email?.substringBefore('@')?.ifEmpty { null }
            ?: "Profile"
    val avatarSeed: String get() = email ?: userId ?: id
}

/**
 * Artwork URLs are not all kobbler's: HDHomeRun channel logos and programme stills point
 * at a third-party CDN, so the bearer token is scoped to the host that issued it.
 */
internal fun imageAuthToken(url: String, current: Profile?, profiles: List<Profile>): String? {
    fun Profile.serves(): Boolean {
        val origin = apiUrl.trimEnd('/')
        return origin.isNotEmpty() && (url == origin || url.startsWith("$origin/"))
    }
    return current?.takeIf { it.serves() }?.token ?: profiles.firstOrNull { it.serves() }?.token
}
