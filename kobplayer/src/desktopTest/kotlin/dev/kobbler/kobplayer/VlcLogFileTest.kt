package dev.kobbler.kobplayer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class VlcLogFileTest {

    @Test
    fun writesTheAudioOutputLogToTheGivenFile() {
        DesktopPlayer.prepareLibraries()
        val log = File.createTempFile("vlc", ".log").apply { delete() }
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val player = DesktopPlayer(scope.coroutineContext, PlayerConfig(hardwareAcceleration = false), vlcLogFile = log.path)
        try {
            player.load(MediaSource.Url(resourceUrl("red_176x100_sar4-3.mkv")))
            runBlocking { withTimeout(10_000) { player.state.first { it == PlayerState.PLAYING } } }
        } finally {
            player.release()
            scope.cancel()
        }
        Thread.sleep(500)
        assertTrue(log.exists() && log.readText().contains("main input debug"), "no VLC debug log in ${log.path}")
        log.delete()
    }
}
