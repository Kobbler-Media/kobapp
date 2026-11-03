package dev.kobbler.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.ui.components.DataCache
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.time.Clock
import dev.kobbler.app.platform.clientKind
import dev.kobbler.app.platform.deviceLabel
import dev.kobbler.app.platform.hostedServer
import dev.kobbler.app.platform.ioDispatcher

private const val LEGACY_KEY_API_URL = "kobbler_api_url"
private const val LEGACY_KEY_TOKEN = "kobbler_token"
private const val KEY_PROFILES = "kobbler_profiles_v1"
private const val KEY_CURRENT_PROFILE = "kobbler_current_profile_id"
private const val KEY_OFFLINE_PIN_PREFIX = "kobbler_offline_pin_"

/** The profile last signed in, read without a UI: what background work acts as. */
fun storedActiveProfile(store: KeyValueStore = PlatformKeyValueStore): Profile? {
    val profiles = store.getString(KEY_PROFILES)
        ?.let { runCatching { Json { ignoreUnknownKeys = true }.decodeFromString<List<Profile>>(it) }.getOrNull() }
        ?: return null
    val id = store.getString(KEY_CURRENT_PROFILE) ?: return null
    return profiles.firstOrNull { it.id == id && !it.needsRelink }
}

private fun offlinePinDigest(profileId: String, pin: String) = dev.kobbler.app.offline.stableHash("$profileId:$pin")
private const val KEY_SHOW_PICKER_ON_LAUNCH = "kobbler_show_picker_on_launch"
private const val KEY_SESSION_TOUCHED_PREFIX = "kobbler_session_touched_"

/** Tokens live 90 days; checking in once a day keeps a wide margin for cheap. */
private const val TOUCH_INTERVAL_MS = 24L * 60 * 60 * 1000
private const val NAMESPACE = "kobbler"

/** Per-user pref keys that must be migrated into the first profile's namespace on upgrade. */
internal val PER_USER_LEGACY_KEYS = listOf(
    "pref_default_audio_lang",
    "pref_default_subtitle_lang",
    "pref_subtitles_enabled",
    "pref_default_playback_speed",
    "pref_streaming_quality",
    "pref_tts_voice",
    "pref_tts_speed",
    "reader_brightness",
    "reader_theme",
    "reader_font_size",
    "reader_line_height",
    "reader_h_padding",
    "reader_font_family",
)

@Serializable
data class PinResponse(
    val pin: String? = null,
    val result: PinResult? = null,
)

@Serializable
data class PinResult(
    val api_url: String,
    val token: String,
)

@Serializable
data class TouchSessionRequest(
    val device_name: String,
    val client_kind: String,
)

@Serializable
data class MintTokenRequest(
    val device_name: String,
)

@Serializable
data class RosterUser(
    val id: String,
    val email: String,
    val role: String = "viewer",
    val display_name: String? = null,
    val avatar_url: String? = null,
)

@Serializable
data class MintedToken(
    val user_id: String,
    val token: String,
)

@Serializable
data class MeResponse(
    val user_id: String = "",
    val email: String = "",
    val role: String = "viewer",
    val display_name: String? = null,
    val avatar_url: String? = null,
    val has_pin: Boolean = false,
    val is_kids: Boolean = false,
)

enum class AuthScreen { Loading, Login, Picker, LoggedIn }

/** Composition-local for the app-wide [AuthState], so deep UI can reach profile state. */
val LocalAuthState = androidx.compose.runtime.compositionLocalOf<AuthState> {
    error("AuthState not provided")
}

class AuthState(
    private val scope: CoroutineScope,
    private val formFactor: FormFactor = FormFactor.Phone,
    private val store: KeyValueStore = PlatformKeyValueStore,
    private val hosted: HostedServer? = hostedServer(),
) {
    var screen by mutableStateOf(AuthScreen.Loading)
        private set

    val profiles: SnapshotStateList<Profile> = mutableStateListOf()

    var currentProfileId by mutableStateOf<String?>(null)
        private set

    val currentProfile: Profile? get() = profiles.firstOrNull { it.id == currentProfileId }

    val profilesByRecentUse: List<Profile> get() = profiles.sortedByDescending { it.lastUsedAtMs }

    val apiUrl: String? get() = currentProfile?.apiUrl
    val token: String? get() = currentProfile?.token
    val role: String get() = currentProfile?.role ?: "viewer"
    val isKids: Boolean get() = currentProfile?.isKids == true

    /** True if the user can perform content management actions (want, monitor). */
    val canWant: Boolean get() = role != "viewer"

    /** When true, always show the picker on launch (default true for TV/Desktop). */
    var showPickerOnLaunch by mutableStateOf(false)
        private set

    /** Mode for the in-progress PIN-link flow. */
    var pinLoginMode by mutableStateOf(PinLoginMode.Replace)
        private set

    /** Profile whose token the in-progress Relink flow replaces. */
    var relinkTargetId by mutableStateOf<String?>(null)
        private set

    // PIN login (server-linking) state
    var pin by mutableStateOf<String?>(null)
        private set
    var pinError by mutableStateOf<String?>(null)
        private set
    var pinPolling by mutableStateOf(false)
        private set

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val httpClient = SharedHttp.apiClient()

    private class StoredProfiles(
        val profiles: List<Profile>,
        val currentProfileId: String?,
        val showPickerOnLaunch: Boolean?,
        val legacy: Profile?,
    )

    init {
        UserSettings.bind { currentProfileId }
        // Reading and parsing the profile blob is the process's first touch of persistent
        // storage; on TV flash that disk hit lands squarely in first-frame time. Keep it
        // off the composition thread and hold AuthScreen.Loading until it lands.
        scope.launch {
            applyStored(withContext(ioDispatcher) { readStoredProfiles() })
            hostedAdopted = hosted?.let { adoptHostedSession(it) } == true
            decideInitialScreen()
            // Every profile, not just the active one: the picker shows each one's name and
            // avatar, and a family TV profile nobody picks for 90 days would otherwise
            // expire even though the device is in daily use.
            refreshAllProfilesFromServer()
        }
    }

    private var hostedAdopted = false

    private fun decideInitialScreen() {
        screen = when {
            hostedAdopted && currentProfile?.hasPin != true -> AuthScreen.LoggedIn
            profiles.isEmpty() -> AuthScreen.Login
            currentProfileId == null -> AuthScreen.Picker
            currentProfile?.needsRelink == true -> AuthScreen.Picker
            showPickerOnLaunch || (currentProfile?.hasPin == true) -> AuthScreen.Picker
            else -> AuthScreen.LoggedIn
        }
    }

    private fun readStoredProfiles(): StoredProfiles {
        val pickerPref = store.getString(KEY_SHOW_PICKER_ON_LAUNCH)?.toBooleanStrictOrNull()
        val raw = store.getString(KEY_PROFILES)
        if (raw != null) {
            return StoredProfiles(
                profiles = runCatching { json.decodeFromString<List<Profile>>(raw) }.getOrNull().orEmpty(),
                currentProfileId = store.getString(KEY_CURRENT_PROFILE),
                showPickerOnLaunch = pickerPref,
                legacy = null,
            )
        }
        val legacyUrl = store.getString(LEGACY_KEY_API_URL)
        val legacyToken = store.getString(LEGACY_KEY_TOKEN)
        return StoredProfiles(
            profiles = emptyList(),
            currentProfileId = null,
            showPickerOnLaunch = pickerPref,
            legacy = if (legacyUrl != null && legacyToken != null) {
                Profile(id = newProfileId(), token = legacyToken, apiUrl = legacyUrl)
            } else null,
        )
    }

    private fun applyStored(stored: StoredProfiles) {
        profiles.addAll(stored.profiles)
        currentProfileId = stored.currentProfileId?.takeIf { id -> profiles.any { it.id == id } }
        stored.legacy?.let { migrated ->
            profiles.add(migrated)
            currentProfileId = migrated.id
            UserSettings.migrateLegacyKeys(PER_USER_LEGACY_KEYS, migrated.id)
            persistProfiles()
            store.remove(LEGACY_KEY_API_URL)
            store.remove(LEGACY_KEY_TOKEN)
        }
        showPickerOnLaunch = stored.showPickerOnLaunch
            ?: (formFactor == FormFactor.TV || formFactor == FormFactor.Desktop)
        if (stored.showPickerOnLaunch == null) {
            // Persist the form-factor default so future launches don't recompute.
            store.putString(KEY_SHOW_PICKER_ON_LAUNCH, showPickerOnLaunch.toString())
        }
    }

    private fun persistProfiles() {
        store.putString(KEY_PROFILES, json.encodeToString<List<Profile>>(profiles.toList()))
        currentProfileId?.let { store.putString(KEY_CURRENT_PROFILE, it) } ?: store.remove(KEY_CURRENT_PROFILE)
    }

    fun updateShowPickerOnLaunch(enabled: Boolean) {
        showPickerOnLaunch = enabled
        store.putString(KEY_SHOW_PICKER_ON_LAUNCH, enabled.toString())
    }

    private val onPicker: Boolean get() = screen == AuthScreen.Picker

    private fun openPicker() {
        screen = AuthScreen.Picker
    }

    /** Switching profile leaves the session: the picker has no way back to it, only forward into a profile. */
    fun showPicker() {
        if (profiles.isEmpty()) {
            screen = AuthScreen.Login
        } else {
            openPicker()
            scope.launch { refreshAllProfilesFromServer() }
        }
    }

    /** Select a profile. Call after any PIN gate has been cleared. */
    fun confirmProfile(profileId: String) {
        if (profiles.none { it.id == profileId }) return
        val switched = currentProfileId != profileId
        currentProfileId = profileId
        updateProfile(profileId) { it.copy(lastUsedAtMs = nowMs()) }
        if (switched) DataCache.clear()
        screen = AuthScreen.LoggedIn
        scope.launch { refreshProfileFromServer(profileId) }
    }

    /** Remove a profile. If it was active, fall back to picker / login. */
    fun removeProfile(profileId: String) {
        val wasCurrent = currentProfileId == profileId
        profiles.removeAll { it.id == profileId }
        if (wasCurrent) {
            currentProfileId = null
            DataCache.clear()
        }
        persistProfiles()
        if (profiles.isEmpty()) {
            screen = AuthScreen.Login
        } else if (wasCurrent) {
            openPicker()
        }
    }

    /** Log out of the active profile (remove it). Other profiles stay linked. */
    fun logout() {
        val id = currentProfileId ?: run {
            if (profiles.isEmpty()) screen = AuthScreen.Login else openPicker()
            return
        }
        removeProfile(id)
    }

    /** Re-read the active profile from the server after changing one of its fields. */
    fun refreshCurrentProfile() {
        val id = currentProfileId ?: return
        scope.launch { refreshProfileFromServer(id) }
    }

    private suspend fun refreshAllProfilesFromServer() {
        profiles.sortedByDescending { it.id == currentProfileId }.map { it.id }.forEach { refreshProfileFromServer(it) }
    }

    private fun nowMs() = Clock.System.now().toEpochMilliseconds()

    private fun updateProfile(id: String, transform: (Profile) -> Profile) {
        val idx = profiles.indexOfFirst { it.id == id }
        if (idx < 0) return
        profiles[idx] = transform(profiles[idx])
        persistProfiles()
    }

    /**
     * Slide the active token's expiry forward, at most daily.
     *
     * Tokens expire 90 days after they are minted and nothing else renews them, so a device
     * used inside that window should never be forced back through pairing. The server owns
     * the new expiry; this only reports that the device is alive and names it.
     */
    private suspend fun touchSession(profileId: String) {
        val profile = profiles.firstOrNull { it.id == profileId } ?: return
        val key = KEY_SESSION_TOUCHED_PREFIX + profileId
        val last = store.getString(key)?.toLongOrNull() ?: 0L
        val nowMs = nowMs()
        if (nowMs - last < TOUCH_INTERVAL_MS) return
        try {
            val resp = httpClient.post("${profile.apiUrl}/api/v1/admin/sessions/refresh") {
                header(HttpHeaders.Authorization, "Bearer ${profile.token}")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(TouchSessionRequest(deviceLabel, clientKind)))
            }
            if (resp.status.isSuccess()) {
                store.putString(key, nowMs.toString())
            } else if (resp.status == HttpStatusCode.Unauthorized) {
                updateProfile(profileId) { it.copy(needsRelink = true) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Offline; the next launch tries again well inside the expiry window.
        }
    }

    /**
     * The Kobbler users this profile's token can see. Admins get everyone, which is what
     * makes one pairing enough to provision a household onto a device.
     */
    suspend fun fetchRoster(): List<RosterUser> {
        val profile = currentProfile ?: return emptyList()
        return try {
            val resp = httpClient.get("${profile.apiUrl}/api/v1/admin/users") {
                header(HttpHeaders.Authorization, "Bearer ${profile.token}")
            }
            if (!resp.status.isSuccess()) return emptyList()
            json.decodeFromString<List<RosterUser>>(resp.bodyAsText())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** User ids already linked on this device, so the picker never offers a duplicate. */
    val linkedUserIds: Set<String> get() = profiles.mapNotNull { it.userId }.toSet()

    /**
     * Mint a token per user with the active admin token and add each as a profile.
     * Returns how many were added.
     */
    suspend fun addHouseholdProfiles(userIds: List<String>): Int {
        val admin = currentProfile ?: return 0
        var added = 0
        for (userId in userIds) {
            if (userId in linkedUserIds) continue
            val minted = try {
                val resp = httpClient.post("${admin.apiUrl}/api/v1/admin/users/$userId/device-token") {
                    header(HttpHeaders.Authorization, "Bearer ${admin.token}")
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(MintTokenRequest(deviceLabel)))
                }
                if (!resp.status.isSuccess()) null
                else json.decodeFromString<MintedToken>(resp.bodyAsText())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: continue

            val id = newProfileId()
            profiles.add(
                Profile(id = id, userId = minted.user_id, token = minted.token, apiUrl = admin.apiUrl)
            )
            added++
            persistProfiles()
            refreshProfileFromServer(id)
        }
        return added
    }

    fun startPinLogin(mode: PinLoginMode = PinLoginMode.Replace, relinkProfileId: String? = null) {
        // A retry from the PIN screen re-enters without the id, so keep the standing target.
        relinkTargetId = if (mode == PinLoginMode.Relink) relinkProfileId ?: relinkTargetId else null
        pinLoginMode = mode
        pinError = null
        pin = null
        pinPolling = true
        scope.launch {
            try {
                val resp = httpClient.post("$PIN_SERVICE/pin/$NAMESPACE")
                val body = json.decodeFromString<PinResponse>(resp.bodyAsText())
                if (body.pin != null) {
                    pin = body.pin
                    pollPin(body.pin)
                } else {
                    pinError = "Failed to create PIN"
                    pinPolling = false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pinError = "Network error: ${e.message}"
                pinPolling = false
            }
        }
    }

    private suspend fun pollPin(originalPin: String) {
        repeat(120) { // 10 minutes at 5s intervals
            if (!pinPolling) return
            delay(5000)
            try {
                val resp = httpClient.post("$PIN_SERVICE/pin/$NAMESPACE/$originalPin")
                // The service reports an expired or already-consumed PIN as 404
                // and no longer mints a replacement.
                if (resp.status == HttpStatusCode.NotFound) {
                    pinError = "PIN expired"
                    pinPolling = false
                    return
                }
                // Guess throttling is shared across callers - back off, do not
                // treat it as expiry.
                if (resp.status == HttpStatusCode.TooManyRequests) {
                    return@repeat
                }
                val body = json.decodeFromString<PinResponse>(resp.bodyAsText())
                if (body.result != null) {
                    onPinLoginSuccess(body.result.api_url, body.result.token)
                    return
                }
                if (body.pin != originalPin) {
                    pinError = "PIN expired"
                    pinPolling = false
                    return
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Network blip — keep polling
            }
        }
        pinError = "PIN timed out"
        pinPolling = false
    }

    private suspend fun onPinLoginSuccess(apiUrl: String, token: String) {
        pinPolling = false
        pin = null

        val relinkId = relinkTargetId
        if (pinLoginMode == PinLoginMode.Relink && relinkId != null) {
            relinkTargetId = null
            // Reuse the existing profile id: per-profile prefs are namespaced by it.
            updateProfile(relinkId) {
                it.copy(token = token, apiUrl = apiUrl, needsRelink = false, lastUsedAtMs = nowMs())
            }
            store.remove(KEY_SESSION_TOUCHED_PREFIX + relinkId)
            currentProfileId = relinkId
            persistProfiles()
            DataCache.clear()
            screen = AuthScreen.LoggedIn
            refreshProfileFromServer(relinkId)
            return
        }

        val id = newProfileId()
        val profile = Profile(
            id = id,
            token = token,
            apiUrl = apiUrl,
            lastUsedAtMs = if (pinLoginMode == PinLoginMode.Replace) nowMs() else 0,
        )
        profiles.add(profile)
        persistProfiles()

        when (pinLoginMode) {
            PinLoginMode.Replace -> {
                currentProfileId = id
                persistProfiles()
                DataCache.clear()
                screen = AuthScreen.LoggedIn
            }
            PinLoginMode.Add, PinLoginMode.Relink -> {
                if (currentProfileId == null || onPicker) openPicker() else screen = AuthScreen.LoggedIn
            }
        }
        refreshProfileFromServer(id)
    }

    /** Mirror a linked profile's server-side fields (role, name, avatar, has_pin) onto the device. */
    private suspend fun refreshProfileFromServer(id: String) {
        val profile = profiles.firstOrNull { it.id == id } ?: return
        try {
            val resp = httpClient.get("${profile.apiUrl}/api/v1/admin/me") {
                header(HttpHeaders.Authorization, "Bearer ${profile.token}")
            }
            if (resp.status == HttpStatusCode.Unauthorized || resp.status == HttpStatusCode.Forbidden) {
                // Keep the profile so relinking reuses its id and its per-profile prefs.
                updateProfile(id) { it.copy(needsRelink = true) }
                // Every other request is about to 401 too, so send them to the picker
                // rather than leave a signed-in shell that fails on every screen.
                if (id == currentProfileId) openPicker()
                return
            }
            val me = runCatching { json.decodeFromString<MeResponse>(resp.bodyAsText()) }.getOrNull()
                ?: return
            updateProfile(id) {
                it.copy(
                    userId = me.user_id.ifEmpty { it.userId },
                    email = me.email.ifEmpty { it.email },
                    role = me.role,
                    name = me.display_name,
                    avatarUrl = me.avatar_url,
                    hasPin = me.has_pin,
                    isKids = me.is_kids,
                    needsRelink = false,
                )
            }
            touchSession(id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Offline or transient; keep cached profile state.
        }
    }

    fun cancelPinLogin() {
        pinPolling = false
        pin = null
        pinError = null
        relinkTargetId = null
        when {
            profiles.isEmpty() -> screen = AuthScreen.Login
            onPicker -> Unit
            currentProfileId != null -> screen = AuthScreen.LoggedIn
            else -> openPicker()
        }
    }

    /** Verify a PIN against the given profile, returning true on success. */
    suspend fun verifyPin(profileId: String, pin: String): Boolean {
        val profile = profiles.firstOrNull { it.id == profileId } ?: return false
        return try {
            val resp = httpClient.post("${profile.apiUrl}/api/v1/admin/me/verify-pin") {
                header(HttpHeaders.Authorization, "Bearer ${profile.token}")
                contentType(ContentType.Application.Json)
                setBody("""{"pin":${Json.encodeToString(pin)}}""")
            }
            val ok = resp.status == HttpStatusCode.NoContent || resp.status == HttpStatusCode.OK
            if (ok) store.putString(KEY_OFFLINE_PIN_PREFIX + profileId, offlinePinDigest(profileId, pin))
            ok
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // With no server to ask, only the PIN it last accepted opens the profile, so its
            // downloads can still be reached.
            store.getString(KEY_OFFLINE_PIN_PREFIX + profileId) == offlinePinDigest(profileId, pin)
        }
    }

    /** Set or replace the PIN on the active profile (uses its own token). */
    suspend fun setPin(pin: String): Boolean {
        val profile = currentProfile ?: return false
        return try {
            val resp = httpClient.put("${profile.apiUrl}/api/v1/admin/me/profile-pin") {
                header(HttpHeaders.Authorization, "Bearer ${profile.token}")
                contentType(ContentType.Application.Json)
                setBody("""{"pin":${Json.encodeToString(pin)}}""")
            }
            val ok = resp.status == HttpStatusCode.NoContent || resp.status == HttpStatusCode.OK
            if (ok) updateProfile(profile.id) { it.copy(hasPin = true) }
            ok
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /** Remove the PIN on the active profile. */
    suspend fun clearPin(): Boolean {
        val profile = currentProfile ?: return false
        return try {
            val resp = httpClient.delete("${profile.apiUrl}/api/v1/admin/me/profile-pin") {
                header(HttpHeaders.Authorization, "Bearer ${profile.token}")
            }
            val ok = resp.status == HttpStatusCode.NoContent || resp.status == HttpStatusCode.OK
            if (ok) updateProfile(profile.id) { it.copy(hasPin = false) }
            ok
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Signs in as whoever the browser's kobbler session belongs to. The `/me` call carries
     * no bearer, so the server answers from the same-origin session cookie.
     */
    private suspend fun adoptHostedSession(host: HostedServer): Boolean {
        val cookieUser = try {
            val resp = httpClient.get("${host.apiUrl}/api/v1/admin/me")
            if (resp.status == HttpStatusCode.Unauthorized) {
                host.signIn(host.apiUrl)
                return false
            }
            if (!resp.status.isSuccess()) return false
            json.decodeFromString<MeResponse>(resp.bodyAsText()).user_id
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
        val linked = profiles.firstOrNull { it.apiUrl == host.apiUrl && it.userId == cookieUser }
        if (linked != null && !linked.needsRelink) {
            currentProfileId = linked.id
            persistProfiles()
            return true
        }
        val token = try {
            val resp = httpClient.post("${host.apiUrl}/api/v1/admin/auth/player-token")
            if (!resp.status.isSuccess()) return false
            json.decodeFromString<PlaybackTokenDto>(resp.bodyAsText()).token
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
        val profile = linked?.copy(token = token, needsRelink = false)
            ?: Profile(id = newProfileId(), userId = cookieUser, token = token, apiUrl = host.apiUrl)
        if (linked == null) profiles.add(profile) else updateProfile(linked.id) { profile }
        currentProfileId = profile.id
        persistProfiles()
        return true
    }

    private fun newProfileId(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..16).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }
}

enum class PinLoginMode { Replace, Add, Relink }
