package dev.kobbler.app.ui.player

import dev.kobbler.app.data.AudioFileDto
import dev.kobbler.app.data.Book
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.UnifiedChapterDto
import dev.kobbler.app.ui.player.audiobook.AudiobookPlayerState
import dev.kobbler.kobplayer.PlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

private fun file(id: String, index: Int, seconds: Double) =
    AudioFileDto(id = id, fileIndex = index, format = "mp3", fileName = "$id.mp3", durationSeconds = seconds)

/** Two files in, thirty seconds into the second, playing. */
internal fun listeningBook(api: KobblerApi, player: FakePlayer) = AudiobookPlayerState(
    Book(id = "b1", title = "Treehouse", author = "Andy", coverUrl = "/covers/b1.jpg"),
    api,
    player,
    CoroutineScope(Dispatchers.Unconfined),
).apply {
    audioFiles = listOf(file("f1", 0, 600.0), file("f2", 1, 900.0))
    chapters = listOf(
        UnifiedChapterDto(id = "c1", chapterIndex = 0, title = "One", startSeconds = 0.0, endSeconds = 600.0, fileId = "f1"),
        UnifiedChapterDto(
            id = "c2", chapterIndex = 1, title = "Two", startSeconds = 0.0, endSeconds = 900.0, fileId = "f2",
            globalStartSeconds = 600.0, globalEndSeconds = 1500.0,
        ),
    )
    dataLoaded = true
    runBlocking { playFileAt(1, 30_000) }
    player.state.value = PlayerState.PLAYING
}
