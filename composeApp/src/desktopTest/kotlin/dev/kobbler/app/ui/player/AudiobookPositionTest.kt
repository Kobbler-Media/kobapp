package dev.kobbler.app.ui.player

import dev.kobbler.app.data.AudioFileDto
import dev.kobbler.app.data.Book
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.TranscriptSegmentDto
import dev.kobbler.app.data.UnifiedChapterDto
import dev.kobbler.app.ui.player.audiobook.AudiobookPlayerState
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals

class AudiobookPositionTest {

    private fun file(id: String, index: Int) =
        AudioFileDto(id = id, fileIndex = index, format = "mp3", fileName = "$id.mp3", durationSeconds = 600.0)

    private fun segment(id: String, fileId: String, start: Double) =
        TranscriptSegmentDto(id = id, fileId = fileId, segmentIndex = 0, startSeconds = start, endSeconds = start + 5, text = id)

    private fun chapter(id: String, globalStart: Double, globalEnd: Double) = UnifiedChapterDto(
        id = id,
        chapterIndex = 0,
        title = id,
        startSeconds = globalStart,
        endSeconds = globalEnd,
        fileId = "f1",
        globalStartSeconds = globalStart,
        globalEndSeconds = globalEnd,
    )

    private val segments = listOf(
        segment("a0", "f1", 0.0),
        segment("b0", "f2", 0.0),
        segment("a10", "f1", 10.0),
        segment("b10", "f2", 10.0),
        segment("a20", "f1", 20.0),
    )

    private val chapters = listOf(
        chapter("c1", 0.0, 300.0),
        chapter("c2", 300.0, 900.0),
        chapter("c3", 900.0, 1200.0),
    )

    private fun state(fileIndex: Int, ms: Long) = AudiobookPlayerState(
        Book(id = "b1", title = "T", author = "A", coverUrl = ""),
        KobblerApi(HttpClient(), "http://127.0.0.1:1", "token"),
        FakePlayer(),
        CoroutineScope(Dispatchers.Unconfined),
    ).apply {
        audioFiles = listOf(file("f1", 0), file("f2", 1), file("f3", 2))
        transcriptSegments = segments
        this.chapters = this@AudiobookPositionTest.chapters
        currentFileIndex = fileIndex
        currentMs = ms
    }

    @Test
    fun `the active transcript line is the last one started in the playing file`() {
        data class Case(val fileIndex: Int, val ms: Long, val expected: Int)
        listOf(
            Case(0, 0, 0),
            Case(0, 9_999, 0),
            Case(0, 10_000, 2),
            Case(0, 59_000, 4),
            Case(1, 0, 1),
            Case(1, 12_000, 3),
            Case(2, 5_000, -1),
            Case(5, 5_000, -1),
        ).forEach { c ->
            assertEquals(c.expected, state(c.fileIndex, c.ms).activeSegmentIndex, "$c")
        }
    }

    @Test
    fun `the active transcript line follows the playhead`() {
        val s = state(0, 0)
        assertEquals(0, s.activeSegmentIndex)
        s.currentMs = 21_000
        assertEquals(4, s.activeSegmentIndex)
        s.currentFileIndex = 1
        assertEquals(3, s.activeSegmentIndex)
    }

    @Test
    fun `chapter and remaining time are measured on the whole book`() {
        data class Case(val fileIndex: Int, val ms: Long, val chapter: String?, val remainingMs: Long?)
        listOf(
            Case(0, 0, "c1", 300_000),
            Case(0, 299_000, "c1", 1_000),
            Case(0, 300_000, "c2", 600_000),
            Case(1, 300_000, "c3", 300_000),
            Case(2, 0, "c3", 0),
        ).forEach { c ->
            val s = state(c.fileIndex, c.ms)
            assertEquals(c.chapter, s.currentChapter?.id, "$c")
            assertEquals(c.remainingMs, s.chapterRemainingMs, "$c")
        }
    }

    @Test
    fun `book offsets follow the file list`() {
        val s = state(2, 1_000)
        assertEquals(listOf(0L, 600_000L, 1_200_000L), s.fileTimeOffsetsMs)
        assertEquals(1_800_000, s.totalBookDurationMs)
        assertEquals(1_201_000, s.globalPositionMs)
        s.audioFiles = s.audioFiles.take(2)
        assertEquals(1_200_000, s.totalBookDurationMs)
    }
}
