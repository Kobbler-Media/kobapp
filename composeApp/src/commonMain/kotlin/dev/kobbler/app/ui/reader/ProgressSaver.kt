package dev.kobbler.app.ui.reader

import kotlinx.coroutines.CancellationException

internal class ProgressSaver<T>(private val send: suspend (T) -> Unit) {
    private var lastSent: T? = null

    suspend fun save(progress: T) {
        if (progress == lastSent) return
        try {
            send(progress)
            lastSent = progress
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
}
