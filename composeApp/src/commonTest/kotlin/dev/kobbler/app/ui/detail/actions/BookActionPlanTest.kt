package dev.kobbler.app.ui.detail.actions

import dev.kobbler.app.ui.detail.actions.BookAction.DownloadAudio
import dev.kobbler.app.ui.detail.actions.BookAction.DownloadBook
import dev.kobbler.app.ui.detail.actions.BookAction.Listen
import dev.kobbler.app.ui.detail.actions.BookAction.Read
import dev.kobbler.app.ui.detail.actions.BookAction.ReadComic
import dev.kobbler.app.ui.detail.actions.BookAction.ViewComic
import dev.kobbler.app.ui.detail.actions.BookAction.Want
import dev.kobbler.app.ui.detail.actions.Stage.Finished
import dev.kobbler.app.ui.detail.actions.Stage.InProgress
import dev.kobbler.app.ui.detail.actions.Stage.Unstarted
import kotlin.test.Test
import kotlin.test.assertEquals

class BookActionPlanTest {

    private val both = BookActionState(readable = true, audio = true, canDownloadText = true, canDownloadAudio = true)

    private data class Case(val name: String, val state: BookActionState, val expected: List<BookAction>)

    private val cases = listOf(
        Case("unstarted reads first", both, listOf(Read(Unstarted), Listen(Unstarted), DownloadBook, DownloadAudio)),
        Case(
            "from audiobooks, listening leads",
            both.copy(listenFirst = true),
            listOf(Listen(Unstarted), Read(Unstarted), DownloadAudio, DownloadBook),
        ),
        Case(
            "reading in progress resumes",
            both.copy(text = BookMedium(percent = 40.0)),
            listOf(Read(InProgress, 40.0), Listen(Unstarted), DownloadBook, DownloadAudio),
        ),
        Case(
            "a listen in progress beats a book not started",
            both.copy(listening = BookMedium(percent = 30.0)),
            listOf(Listen(InProgress, 30.0), Read(Unstarted), DownloadBook, DownloadAudio),
        ),
        Case(
            "a finished book is read again",
            BookActionState(readable = true, text = BookMedium(percent = 100.0)),
            listOf(Read(Finished)),
        ),
        Case(
            "the server's finished threshold counts as finished",
            BookActionState(readable = true, text = BookMedium(percent = 95.0)),
            listOf(Read(Finished)),
        ),
        Case(
            "a finished audiobook is listened to again",
            BookActionState(audio = true, listening = BookMedium(percent = 99.0)),
            listOf(Listen(Finished)),
        ),
        Case(
            "a finished listen still leads after a started read",
            both.copy(listenFirst = true, text = BookMedium(percent = 10.0), listening = BookMedium(percent = 100.0)),
            listOf(Read(InProgress, 10.0), Listen(Finished), DownloadAudio, DownloadBook),
        ),
        Case("a comic alone is read", BookActionState(comic = true), listOf(ReadComic)),
        Case("a comic beside an ebook is viewed", BookActionState(readable = true, comic = true), listOf(Read(Unstarted), ViewComic)),
        Case("a book not in the library is wanted", BookActionState(wantable = true), listOf(Want)),
        Case(
            "offline, only what is on this device opens, and only started downloads show",
            both.copy(offline = true, text = BookMedium(onDevice = true, downloadStarted = true)),
            listOf(Read(Unstarted), DownloadBook),
        ),
        Case(
            "offline, a book being downloaded is not readable yet",
            both.copy(offline = true, listening = BookMedium(downloadStarted = true)),
            listOf(DownloadAudio),
        ),
        Case("nothing to open offers nothing", BookActionState(), emptyList()),
    )

    @Test
    fun `actions per state`() {
        for (case in cases) assertEquals(case.expected, bookActions(case.state), case.name)
    }
}
