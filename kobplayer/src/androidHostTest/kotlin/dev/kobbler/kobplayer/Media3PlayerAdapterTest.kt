package dev.kobbler.kobplayer

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/** Seeks from the media session (remote FF/RW, Assistant, cast) reach the player once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Media3PlayerAdapterTest {

    private val cases = listOf<Pair<String, (Media3PlayerAdapter) -> Unit>>(
        "seek to" to { it.seekTo(30_000) },
        "fast forward" to { it.seekForward() },
        "rewind" to { it.seekBack() },
    )

    @Test
    fun `media session seeks reach the player`() {
        val failures = cases.mapNotNull { (name, seek) ->
            val sought = mutableListOf<Long>()
            val adapter = Media3PlayerAdapter(
                StubPlayer().player,
                positionMs = { 60_000 },
                durationMs = { 600_000 },
                seekPlayerTo = { sought += it },
                seekBackIncrementMs = { 10_000 },
                seekForwardIncrementMs = { 10_000 },
            )
            val outcome = runCatching { seek(adapter) }.exceptionOrNull()
            adapter.cleanup()
            when {
                outcome != null -> "$name: ${outcome::class.simpleName}"
                sought.size != 1 -> "$name: sought $sought"
                else -> null
            }
        }
        assertEquals(emptyList(), failures)
    }
}
