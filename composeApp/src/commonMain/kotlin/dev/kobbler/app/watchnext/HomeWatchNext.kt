package dev.kobbler.app.watchnext

import kotlinx.coroutines.flow.MutableStateFlow

fun interface HomeWatchNextPublisher {
    fun refresh(delayMs: Long)
}

/** The launcher's Watch Next row. Only a TV install sets [publisher]; everywhere else it stays null. */
object HomeWatchNext {
    var publisher: HomeWatchNextPublisher? = null
    val launches = MutableStateFlow<WatchNextLaunch?>(null)
}
