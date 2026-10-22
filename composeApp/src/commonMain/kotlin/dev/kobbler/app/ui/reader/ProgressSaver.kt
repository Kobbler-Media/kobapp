package dev.kobbler.app.ui.reader

import kotlinx.coroutines.CancellationException

internal class ProgressSaver(private val send: suspend (percent: Double) -> Unit) {
    private var lastSent: Double? = null

    suspend fun save(percent: Double) {
        if (percent == lastSent) return
        try {
            send(percent)
            lastSent = percent
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
}
