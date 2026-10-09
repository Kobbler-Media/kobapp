package dev.kobbler.app.data

/** Update info from the kobup on-device app (via ContentProvider IPC on Android). */
data class AppUpdate(
    val versionName: String,
    val versionCode: Long,
)

/**
 * Platform-specific kobup integration. On Android, queries the kobup app's
 * ContentProvider for update status. No-op on other platforms.
 */
expect object KobupUpdater {
    /** Check if a newer version is available via the kobup app. Null if up-to-date, unknown, or not Android. */
    suspend fun checkForUpdate(): AppUpdate?

    /** Open the kobup app to handle the update (download + install). No-op if kobup not installed. */
    fun openForUpdate()
}
