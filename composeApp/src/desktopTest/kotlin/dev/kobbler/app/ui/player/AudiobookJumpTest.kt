package dev.kobbler.app.ui.player

import dev.kobbler.app.data.AudioFileDto
import dev.kobbler.app.data.Book
import dev.kobbler.app.data.Bookmark
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.UnifiedChapterDto
import dev.kobbler.app.ui.player.audiobook.AudiobookPlayerState
import io.ktor.client.HttpClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A jump across files is part of opening that file, not a correction issued afterwards.
 *
 * The seek used to be launched 500ms behind the load, on the bet that the demuxer would
 * be ready by then. Over a network it often is not, VLC drops a seek against an unparsed
 * stream, and the chapter the viewer asked for started from the top of the file instead.
 */
class AudiobookJumpTest {

    private val api = KobblerApi(HttpClient(), "http://127.0.0.1:1", "token")

    private fun file(id: String, index: Int, durationSeconds: Double?) =
        AudioFileDto(id = id, fileIndex = index, format = "M4B", fileName = "$id.m4b", durationSeconds = durationSeconds)

    private fun chapter(id: String, fileId: String, startSeconds: Double, globalStartSeconds: Double) =
        UnifiedChapterDto(
            id = id,
            chapterIndex = 0,
            title = id,
            startSeconds = startSeconds,
            endSeconds = startSeconds + 60,
            fileId = fileId,
            globalStartSeconds = globalStartSeconds,
            globalEndSeconds = globalStartSeconds + 60,
        )

    private fun state(player: FakePlayer, files: List<AudioFileDto>, chapters: List<UnifiedChapterDto>) =
        AudiobookPlayerState(Book(id = "b1", title = "T", author = "A", coverUrl = ""), api, player, scope())
            .also { it.audioFiles = files; it.chapters = chapters }

    private fun scope() = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)

    @Test
    fun `a chapter in another file opens that file at the chapter`() = runTest {
        val player = FakePlayer()
        val state = state(
            player,
            files = listOf(file("f1", 0, 600.0), file("f2", 1, 600.0)),
            chapters = listOf(chapter("c2", fileId = "f2", startSeconds = 120.0, globalStartSeconds = 720.0)),
        )

        state.jumpToChapter(state.chapters.first())

        assertEquals(120, player.loadedSource?.startTimeSec)
        assertTrue(
            player.calls.none { it.startsWith("seek") },
            "the jump seeked instead of opening at the chapter: ${player.calls}",
        )
        assertEquals(1, state.currentFileIndex)
    }

    @Test
    fun `a chapter in the current file is a seek, not a reload`() = runTest {
        val player = FakePlayer()
        val state = state(
            player,
            files = listOf(file("f1", 0, 600.0)),
            chapters = listOf(chapter("c1", fileId = "f1", startSeconds = 300.0, globalStartSeconds = 300.0)),
        )

        state.jumpToChapter(state.chapters.first())

        assertTrue(player.calls.none { it == "load" }, "reopened the file it was already playing: ${player.calls}")
        assertTrue("seek:300000" in player.calls, "did not seek to the chapter: ${player.calls}")
    }

    @Test
    fun `a chapter lands on its own file even when an earlier file has no duration`() = runTest {
        // Durations come from a probe that has not necessarily run. Routing a jump
        // through the summed timeline treats a missing one as zero, which drags every
        // later file's offset back and lands the jump in the wrong file entirely. The
        // chapter names its file, so the timeline is not in the path at all.
        val player = FakePlayer()
        val state = state(
            player,
            files = listOf(file("f1", 0, null), file("f2", 1, 600.0)),
            chapters = listOf(chapter("c2", fileId = "f2", startSeconds = 90.0, globalStartSeconds = 690.0)),
        )

        state.jumpToChapter(state.chapters.first())

        assertEquals(1, state.currentFileIndex)
        assertEquals(90, player.loadedSource?.startTimeSec)
    }

    @Test
    fun `a bookmark in another file opens that file at the bookmark`() = runTest {
        val player = FakePlayer()
        val state = state(player, files = listOf(file("f1", 0, 600.0), file("f2", 1, 600.0)), chapters = emptyList())

        state.jumpToBookmark(
            Bookmark(
                id = "bm1", bookId = "b1", label = "L", createdAt = 0L,
                fileId = "f2", positionSeconds = 45.0,
            ),
        )

        assertEquals(45, player.loadedSource?.startTimeSec)
        assertTrue(
            player.calls.none { it.startsWith("seek") },
            "the jump seeked instead of opening at the bookmark: ${player.calls}",
        )
    }

    @Test
    fun `skipping back across a file boundary opens the previous file at the target`() = runTest {
        val player = FakePlayer()
        val state = state(player, files = listOf(file("f1", 0, 600.0), file("f2", 1, 600.0)), chapters = emptyList())
        state.currentFileIndex = 1
        state.currentMs = 10_000L

        state.skipBack()

        assertEquals(0, state.currentFileIndex)
        assertEquals(580, player.loadedSource?.startTimeSec)
    }
}
