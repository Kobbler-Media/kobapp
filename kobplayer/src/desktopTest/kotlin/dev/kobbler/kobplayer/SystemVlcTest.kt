package dev.kobbler.kobplayer

import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class SystemVlcTest {

    // An installed VLC 3 gave its libvlccore the soname ours had. Whenever that copy was in
    // the process first, our libvlc bound to it, and the first call into it crashed.
    @Test
    fun bundledLibVlcNeverBindsToTheSystemCore() {
        val systemCore = File("/usr/lib/libvlccore.so.9")
        val python = listOf("/usr/bin/python3", "/usr/bin/python").map(::File).firstOrNull { it.canExecute() }
        assumeTrue("needs a system libvlccore and python", systemCore.exists() && python != null)
        val ours = File(System.getProperty("kobplayer.libvlc.dir"), "libvlc.so")

        val probe = """
            import ctypes, sys
            ctypes.CDLL(sys.argv[1], mode=ctypes.RTLD_GLOBAL)
            ctypes.CDLL(sys.argv[2])
            print(open("/proc/self/maps").read())
        """.trimIndent()
        val process = ProcessBuilder(python!!.path, "-I", "-c", probe, systemCore.path, ours.path)
            .redirectErrorStream(true).start()
        val maps = process.inputStream.bufferedReader().readText()
        process.waitFor()

        assertTrue(File(ours.parentFile, "libvlccore.so").path in maps, "our libvlc did not load our libvlccore:\n$maps")
    }
}
