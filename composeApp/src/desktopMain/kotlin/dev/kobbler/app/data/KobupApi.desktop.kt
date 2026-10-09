package dev.kobbler.app.data

actual object KobupUpdater {
    actual suspend fun checkForUpdate(): AppUpdate? = null
    actual fun openForUpdate() {}
}
