package dev.kobbler.app.ui.player

import androidx.compose.runtime.snapshots.Snapshot
import dev.kobbler.app.data.AudioFileDto
import dev.kobbler.app.data.Book
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.ui.player.audiobook.AudiobookPlayerState
import dev.kobbler.kobplayer.Loudness
import dev.kobbler.kobplayer.loudnessGain
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test

/** Audiobooks never levelled at all, though every file's loudness was measured on import. */
class AudiobookLevellingTest {

    private fun file(id: String, lufs: Double?, peak: Double?) = AudioFileDto(
        id = id, fileIndex = 0, format = "mp3", fileName = "$id.mp3", durationSeconds = 600.0,
        lufs = lufs, downmixPeakDb = peak,
    )

    private suspend fun FakePlayer.awaitGain(gain: Float) = withTimeout(5_000) {
        while (normalization != gain) {
            Snapshot.sendApplyNotifications()
            delay(10)
        }
    }

    @Test
    fun `each file plays at its own level and leaving the book hands the player back flat`() = runBlocking {
        val player = FakePlayer()
        val state = AudiobookPlayerState(
            book = Book(id = "bk", title = "Levels", author = "A", coverUrl = ""),
            api = KobblerApi(HttpClient(), "http://127.0.0.1:1", "token"),
            player = player,
            scope = CoroutineScope(Dispatchers.Unconfined),
        ).apply {
            audioFiles = listOf(file("quiet", -26.0, -9.0), file("hot", -23.04, -1.6), file("unmeasured", null, null))
        }
        val leveller = CoroutineScope(Dispatchers.Default).launch { state.levelLoudness() }

        state.playFileAt(0, positionMs = 0)
        player.awaitGain(loudnessGain(Loudness(-26.0, -9.0)))
        state.playFileAt(1, positionMs = 0)
        player.awaitGain(loudnessGain(Loudness(-23.04, -1.6)))
        state.playFileAt(2, positionMs = 0)
        player.awaitGain(1f)
        state.playFileAt(0, positionMs = 0)
        player.awaitGain(loudnessGain(Loudness(-26.0, -9.0)))

        leveller.cancelAndJoin()
        player.awaitGain(1f)
    }
}
