package dev.kobbler.kobplayer

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
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
                stubPlayer(),
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

    private fun stubPlayer(): Player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
        when (method.name) {
            "getState" -> MutableStateFlow(PlayerState.PLAYING)
            "getCurrentMs", "getDurationMs" -> MutableStateFlow(0L)
            "getPlaybackSpeed" -> MutableStateFlow(1f)
            "isMuted", "isBuffering", "isPlaying" -> MutableStateFlow(false)
            "getMaxVolume" -> 1f
            else -> if (method.returnType == kotlinx.coroutines.flow.StateFlow::class.java) MutableStateFlow(null) else null
        }
    } as Player
}
