package dev.kobbler.app.ui.detail.actions

import dev.kobbler.app.data.Episode
import dev.kobbler.app.data.TvShow
import dev.kobbler.app.ui.detail.isWantedNow
import dev.kobbler.app.ui.detail.isWatchedNow
import dev.kobbler.app.ui.detail.actions.ShowAction.*
import dev.kobbler.app.ui.player.resumeRequestSeconds

internal data class EpisodeRef(val seasonIdx: Int, val episodeIdx: Int)

internal sealed interface ShowAction {
    sealed interface OnEpisode : ShowAction {
        val at: EpisodeRef
    }
    data class Resume(override val at: EpisodeRef, val positionSeconds: Int) : OnEpisode
    data class Stream(override val at: EpisodeRef) : OnEpisode
    data class Play(override val at: EpisodeRef) : OnEpisode
    data class Rewatch(override val at: EpisodeRef) : OnEpisode
    data class NextEpisode(override val at: EpisodeRef) : OnEpisode
    data class FromStart(override val at: EpisodeRef) : OnEpisode
    data class WantEpisode(override val at: EpisodeRef, val episodeId: String) : OnEpisode
    data object DownloadAll : ShowAction
    data object AutoDownload : ShowAction
    data object StartOver : ShowAction
    data object WantShow : ShowAction
    data object Watchlist : ShowAction
    data object Keep : ShowAction
    data object Kids : ShowAction
}

internal data class ShowDownloads(val allHeld: Boolean, val following: Boolean)

internal data class ShowActionState(
    val show: TvShow,
    val selectedSeasonIdx: Int = 0,
    val watched: Map<String, Boolean> = emptyMap(),
    val wanted: Map<String, Boolean> = emptyMap(),
    val canPlayVideo: Boolean = true,
    val onDevice: Set<String>? = null,
    val connected: Boolean = true,
    val canWant: Boolean = false,
    val downloads: ShowDownloads? = null,
    val canKeep: Boolean = false,
    val canManageKids: Boolean = false,
)

internal fun showActions(state: ShowActionState): List<ShowAction> = with(state) {
    val online = onDevice == null
    val playable: (Episode) -> Boolean = { it.filePath != null && (onDevice == null || it.id in onDevice) }
    val unwatched: (Episode) -> Boolean = { playable(it) && !it.isWatchedNow(watched) }
    val resumeEpisode = show.continueEpisode?.let { show.episodeAt(EpisodeRef(it.seasonIdx, it.episodeIdx)) }
    val lead = if (canPlayVideo) lead(playable, unwatched) else null
    buildList {
        lead?.let(::add)
        if (lead is Resume || lead is Stream) {
            show.nextAfter(lead.at, playable)?.let { add(NextEpisode(it)) }
            if (lead is Resume) add(FromStart(lead.at))
        }
        val wantable = resumeEpisode?.takeIf { connected && online && it.canBeWanted(canWant) && !it.isWantedNow(wanted) }
        val resumeAt = show.continueEpisode?.let { EpisodeRef(it.seasonIdx, it.episodeIdx) }
        if (wantable?.id != null && resumeAt != null) add(WantEpisode(resumeAt, wantable.id))
        val hasFiles = show.seasons.any { s -> s.episodes.any { it.filePath != null } }
        if (downloads != null && online && canPlayVideo && hasFiles) {
            if (!downloads.allHeld || !downloads.following) add(DownloadAll)
            add(AutoDownload)
        }
        if (!connected || !online) return@buildList
        val position = resumeEpisode?.let { if (it.id in watched) 0 else show.continueEpisode.positionSeconds } ?: 0
        val history = position > 0 || show.seasons.any { s -> s.episodes.any { it.id != null && it.isWatchedNow(watched) } }
        if (history && lead !is Rewatch) add(StartOver)
        if (canWant) {
            add(WantShow)
            add(Watchlist)
        }
        if (canKeep) add(Keep)
        if (canManageKids) add(Kids)
    }
}

private fun ShowActionState.lead(playable: (Episode) -> Boolean, unwatched: (Episode) -> Boolean): ShowAction? {
    show.continueEpisode?.let { resume ->
        val at = EpisodeRef(resume.seasonIdx, resume.episodeIdx)
        val episode = show.episodeAt(at) ?: return@let
        if (episode.filePath == null) return if (onDevice == null) Stream(at) else null
        val position = if (episode.id in watched) 0 else resume.positionSeconds
        return if (resumeRequestSeconds(position) != null) Resume(at, position) else Play(at)
    }
    val pick: (List<Episode>) -> Int = { eps -> if (show.isSport) eps.indexOfLast(unwatched) else eps.indexOfFirst(unwatched) }
    show.seasons.getOrNull(selectedSeasonIdx)?.let { season ->
        pick(season.episodes).takeIf { it >= 0 }?.let { return Play(EpisodeRef(selectedSeasonIdx, it)) }
    }
    show.firstEpisode(unwatched)?.let { return Play(it) }
    return show.firstEpisode(playable)?.let { Rewatch(it) }
}

internal fun TvShow.episodeAt(at: EpisodeRef): Episode? = seasons.getOrNull(at.seasonIdx)?.episodes?.getOrNull(at.episodeIdx)

internal fun TvShow.firstEpisode(matching: (Episode) -> Boolean): EpisodeRef? {
    val order = seasons.indices.filter { seasons[it].number > 0 } + seasons.indices.filter { seasons[it].number <= 0 }
    for (s in order) {
        val e = seasons[s].episodes.indexOfFirst(matching)
        if (e >= 0) return EpisodeRef(s, e)
    }
    return null
}

private fun TvShow.nextAfter(at: EpisodeRef, playable: (Episode) -> Boolean): EpisodeRef? {
    val episodes = seasons.getOrNull(at.seasonIdx)?.episodes ?: return null
    return (at.episodeIdx + 1 until episodes.size).firstOrNull { playable(episodes[it]) }?.let { EpisodeRef(at.seasonIdx, it) }
}
