package dev.kobbler.kobplayer

import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.log.LogLevel
import java.io.FileWriter
import java.time.LocalTime

// libvlc has no file logger of its own (`--file-logging` is a VLC interface module), and
// a desktop launch throws stderr away. Appends, because every player instance opens it.
internal class VlcLogFile(factory: MediaPlayerFactory, path: String) {
    private val out = FileWriter(path, true).buffered()
    private val log = factory.application().newLog().apply {
        level = LogLevel.DEBUG
        addLogListener { level, module, _, _, name, _, _, message ->
            synchronized(out) {
                out.appendLine("${LocalTime.now()} $module $name ${level.name.lowercase()}: $message")
                out.flush()
            }
        }
    }

    fun close() {
        log.release()
        synchronized(out) { out.close() }
    }
}
