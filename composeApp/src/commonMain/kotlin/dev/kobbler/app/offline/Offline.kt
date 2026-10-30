package dev.kobbler.app.offline

import dev.kobbler.app.data.SharedHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** The one offline engine for the process; null where the platform keeps nothing on disk. */
object Offline {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val engine: OfflineEngine? by lazy {
        val fs = platformOfflineFs() ?: return@lazy null
        OfflineEngine(OfflineStore(fs, scope), Reachability(scope), scope)
            .also { it.downloader.start() }
    }
}

/** Signs the engine in as the last active profile, for work that runs with no UI to have done it. */
fun OfflineEngine.attachStoredProfile(): Boolean {
    if (api.value != null) return true
    val profile = dev.kobbler.app.data.storedActiveProfile() ?: return false
    attach(
        dev.kobbler.app.data.KobblerApi(
            SharedHttp.apiClient(), profile.apiUrl, profile.token,
            canWant = profile.role != "viewer", offline = this,
        ),
        kids = profile.isKids,
    )
    return true
}
