package dev.kobbler.app.ui.detail.actions

import dev.kobbler.app.data.ContinueEpisode
import dev.kobbler.app.data.Episode
import dev.kobbler.app.data.Season
import dev.kobbler.app.data.TvShow
import dev.kobbler.app.ui.detail.actions.ShowAction.AutoDownload
import dev.kobbler.app.ui.detail.actions.ShowAction.DownloadAll
import dev.kobbler.app.ui.detail.actions.ShowAction.FromStart
import dev.kobbler.app.ui.detail.actions.ShowAction.Keep
import dev.kobbler.app.ui.detail.actions.ShowAction.Kids
import dev.kobbler.app.ui.detail.actions.ShowAction.NextEpisode
import dev.kobbler.app.ui.detail.actions.ShowAction.Play
import dev.kobbler.app.ui.detail.actions.ShowAction.Resume
import dev.kobbler.app.ui.detail.actions.ShowAction.Rewatch
import dev.kobbler.app.ui.detail.actions.ShowAction.StartOver
import dev.kobbler.app.ui.detail.actions.ShowAction.Stream
import dev.kobbler.app.ui.detail.actions.ShowAction.WantEpisode
import dev.kobbler.app.ui.detail.actions.ShowAction.WantShow
import dev.kobbler.app.ui.detail.actions.ShowAction.Watchlist
import kotlin.test.Test
import kotlin.test.assertEquals

class ShowActionPlanTest {

    private fun show(
        watched: (Int, Int) -> Boolean = { _, _ -> false },
        file: (Int, Int) -> Boolean = { _, _ -> true },
        continueAt: Triple<Int, Int, Int>? = null,
        sport: Boolean = false,
    ) = TvShow(
        id = "show", title = "VisionQuest", year = 2026, posterUrl = "", isSport = sport,
        seasons = (0..2).map { s ->
            Season(number = s, episodes = (1..2).map { e ->
                Episode(id = "e$s-$e", number = e, title = "", filePath = "/e$s-$e.mkv".takeIf { file(s, e) }, isWatched = watched(s, e))
            })
        },
        continueEpisode = continueAt?.let { (s, e, pos) -> ContinueEpisode(s, e, "e$s-${e + 1}", pos) },
    )

    private val everythingWatched = show(watched = { _, _ -> true })
    private val online = ShowActionState(show = show(), canWant = true, downloads = ShowDownloads(allHeld = false, following = false))
    private fun at(s: Int, e: Int) = EpisodeRef(s, e)

    private data class Case(val name: String, val state: ShowActionState, val expected: List<ShowAction>)

    private val cases = listOf(
        Case(
            "unstarted plays the first episode",
            online.copy(show = show(continueAt = Triple(1, 0, 0))),
            listOf(Play(at(1, 0)), DownloadAll, AutoDownload, WantShow, Watchlist),
        ),
        Case(
            "in progress resumes, then offers the next episode and the start of this one",
            online.copy(show = show(watched = { s, _ -> s == 0 }, continueAt = Triple(1, 0, 600))),
            listOf(Resume(at(1, 0), 600), NextEpisode(at(1, 1)), FromStart(at(1, 0)), DownloadAll, AutoDownload, StartOver, WantShow, Watchlist),
        ),
        Case(
            "next available plays it, with nothing to restart",
            online.copy(show = show(watched = { s, _ -> s < 2 }, continueAt = Triple(2, 0, 0))),
            listOf(Play(at(2, 0)), DownloadAll, AutoDownload, StartOver, WantShow, Watchlist),
        ),
        Case(
            "all watched rewatches the first episode of the first season with files, and Rewatch is the reset",
            online.copy(show = everythingWatched),
            listOf(Rewatch(at(1, 0)), DownloadAll, AutoDownload, WantShow, Watchlist),
        ),
        Case(
            "all watched skips a season with no files",
            online.copy(show = show(watched = { _, _ -> true }, file = { s, _ -> s != 1 })),
            listOf(Rewatch(at(2, 0)), DownloadAll, AutoDownload, WantShow, Watchlist),
        ),
        Case(
            "no playable files leaves what the server can still do",
            online.copy(show = show(file = { _, _ -> false })),
            listOf(WantShow, Watchlist),
        ),
        Case(
            "a streamable next episode is streamed and can be wanted",
            online.copy(show = show(watched = { s, _ -> s < 2 }, file = { s, _ -> s < 2 }, continueAt = Triple(2, 0, 0))),
            listOf(Stream(at(2, 0), 0), WantEpisode(at(2, 0), "e2-1"), DownloadAll, AutoDownload, StartOver, WantShow, Watchlist),
        ),
        Case(
            "a stream part-watched resumes where it stopped and can start over",
            online.copy(show = show(watched = { s, _ -> s < 2 }, file = { s, _ -> s < 2 }, continueAt = Triple(2, 0, 600))),
            listOf(Stream(at(2, 0), 600), FromStart(at(2, 0)), WantEpisode(at(2, 0), "e2-1"), DownloadAll, AutoDownload, StartOver, WantShow, Watchlist),
        ),
        Case(
            "everything downloaded and followed only offers the follow toggle",
            online.copy(show = everythingWatched, downloads = ShowDownloads(allHeld = true, following = true)),
            listOf(Rewatch(at(1, 0)), AutoDownload, WantShow, Watchlist),
        ),
        Case(
            "no download support offers no downloads",
            online.copy(show = everythingWatched, downloads = null),
            listOf(Rewatch(at(1, 0)), WantShow, Watchlist),
        ),
        Case(
            "offline rewatches from what this device holds and drops server actions",
            online.copy(show = everythingWatched, onDevice = setOf("e2-2")),
            listOf(Rewatch(at(2, 1))),
        ),
        Case(
            "offline with nothing held offers nothing",
            online.copy(show = everythingWatched, onDevice = emptySet()),
            emptyList(),
        ),
        Case(
            "no video playback keeps only the non-playing actions",
            online.copy(show = everythingWatched, canPlayVideo = false),
            listOf(StartOver, WantShow, Watchlist),
        ),
        Case(
            "without a server only playback is left",
            ShowActionState(show = everythingWatched, connected = false),
            listOf(Rewatch(at(1, 0))),
        ),
        Case(
            "keep and kids access come last",
            online.copy(show = everythingWatched, downloads = null, canKeep = true, canManageKids = true),
            listOf(Rewatch(at(1, 0)), WantShow, Watchlist, Keep, Kids),
        ),
        Case(
            "unwatching the resume episode here forgets its position",
            online.copy(show = show(continueAt = Triple(1, 0, 600)), watched = mapOf("e1-1" to false), downloads = null),
            listOf(Play(at(1, 0)), WantShow, Watchlist),
        ),
        Case(
            "with no next-up, the selected season's first unwatched episode plays",
            online.copy(show = show(watched = { s, _ -> s == 1 }), selectedSeasonIdx = 2, downloads = null),
            listOf(Play(at(2, 0)), StartOver, WantShow, Watchlist),
        ),
        Case(
            "a sport plays the selected season's newest unwatched event",
            online.copy(show = show(sport = true), selectedSeasonIdx = 2, downloads = null),
            listOf(Play(at(2, 1)), WantShow, Watchlist),
        ),
        Case(
            "a selected season already watched falls back to the show's first unwatched episode",
            online.copy(show = show(watched = { s, _ -> s != 2 }), selectedSeasonIdx = 1, downloads = null),
            listOf(Play(at(2, 0)), StartOver, WantShow, Watchlist),
        ),
    )

    @Test
    fun `actions per state`() {
        for (case in cases) assertEquals(case.expected, showActions(case.state), case.name)
    }
}
