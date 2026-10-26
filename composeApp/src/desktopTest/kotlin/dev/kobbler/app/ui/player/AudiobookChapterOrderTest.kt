package dev.kobbler.app.ui.player

import dev.kobbler.app.data.AudioFileDto
import dev.kobbler.app.data.Book
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.UnifiedChapterDto
import dev.kobbler.app.ui.player.audiobook.AudiobookPlayerState
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals

class AudiobookChapterOrderTest {

    private fun chapter(title: String, start: Double, end: Double) = UnifiedChapterDto(
        id = title, chapterIndex = 0, title = title, startSeconds = start, endSeconds = end,
        fileId = "f1", globalStartSeconds = start, globalEndSeconds = end,
    )

    private fun state() = AudiobookPlayerState(
        Book(id = "b1", title = "Max and the Midknights", author = "Lincoln Peirce", coverUrl = ""),
        KobblerApi(HttpClient(), "http://127.0.0.1:1", "token"),
        FakePlayer(),
        CoroutineScope(Dispatchers.Unconfined),
    ).apply {
        audioFiles = listOf(AudioFileDto(id = "f1", fileIndex = 0, format = "m4b", fileName = "f1.m4b", durationSeconds = 4_000.0))
        chapters = listOf(
            chapter("Chapter five", 2_348.0, 3_027.0),
            chapter("Chapter 1", 25.0, 620.0),
            chapter("Chapter 6", 3_027.0, 4_000.0),
            chapter("Chapter two", 620.0, 2_348.0),
        )
    }

    @Test
    fun `chapters play and list in the order they start`() {
        val state = state()
        assertEquals(listOf("Chapter 1", "Chapter two", "Chapter five", "Chapter 6"), state.chapters.map { it.title })
    }

    @Test
    fun `the current chapter is the one the playhead is in`() {
        val state = state()
        listOf(100_000L to "Chapter 1", 2_400_000L to "Chapter five", 3_500_000L to "Chapter 6").forEach { (ms, title) ->
            state.currentMs = ms
            assertEquals(title, state.currentChapter?.title, "at ${ms}ms")
        }
    }
}
