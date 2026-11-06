package dev.kobbler.app.ui.detail.actions

internal enum class Stage { Unstarted, InProgress, Finished }

internal sealed interface BookAction {
    data class Read(val stage: Stage, val progress: Double? = null) : BookAction
    data class Listen(val stage: Stage, val progress: Double? = null) : BookAction
    data object ReadComic : BookAction
    data object ViewComic : BookAction
    data object DownloadBook : BookAction
    data object DownloadAudio : BookAction
    data object Want : BookAction
}

internal data class BookMedium(
    val percent: Double? = null,
    val onDevice: Boolean = false,
    val downloadStarted: Boolean = false,
)

internal data class BookActionState(
    val readable: Boolean = false,
    val comic: Boolean = false,
    val audio: Boolean = false,
    val listenFirst: Boolean = false,
    val text: BookMedium = BookMedium(),
    val listening: BookMedium = BookMedium(),
    val offline: Boolean = false,
    val canDownloadText: Boolean = false,
    val canDownloadAudio: Boolean = false,
    val wantable: Boolean = false,
)

internal const val FinishedPercent = 95.0

internal fun stageOf(percent: Double?): Stage = when {
    percent == null || percent <= 0.0 -> Stage.Unstarted
    percent >= FinishedPercent -> Stage.Finished
    else -> Stage.InProgress
}

internal fun bookActions(state: BookActionState): List<BookAction> = with(state) {
    fun BookMedium.opens() = !offline || onDevice
    fun BookMedium.progress() = percent.takeIf { stageOf(it) == Stage.InProgress }
    val read = if (readable && text.opens()) BookAction.Read(stageOf(text.percent), text.progress()) else null
    val listen = if (audio && listening.opens()) BookAction.Listen(stageOf(listening.percent), listening.progress()) else null
    val stageOf: (BookAction) -> Stage = { (it as? BookAction.Read)?.stage ?: (it as BookAction.Listen).stage }
    val media = listOfNotNull(read, listen).let { if (listenFirst) it.reversed() else it }
        .sortedBy { if (stageOf(it) == Stage.InProgress) 0 else 1 }
    fun BookMedium.downloadable(can: Boolean) = can && (!offline || downloadStarted)
    val downloads = listOfNotNull(
        BookAction.DownloadBook.takeIf { text.downloadable(canDownloadText) },
        BookAction.DownloadAudio.takeIf { listening.downloadable(canDownloadAudio) },
    ).let { if (listenFirst) it.reversed() else it }
    buildList {
        addAll(media)
        if (comic && (!offline || text.onDevice)) add(if (readable) BookAction.ViewComic else BookAction.ReadComic)
        addAll(downloads)
        if (wantable && !offline) add(BookAction.Want)
    }
}
