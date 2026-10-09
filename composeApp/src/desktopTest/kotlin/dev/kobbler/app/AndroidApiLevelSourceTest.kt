package dev.kobbler.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Against compileSdk 35+ a `MutableList.removeLast()` binds to java.util.List's member, which
 * Android only ships from API 35. Everything below that — the Google TV Streamer is API 34 —
 * dies with NoSuchMethodError: goBack() did, closing the app on every BACK from a page. It
 * compiles and passes on the JDK 21 desktop tests, and Android lint does not see KMP sources,
 * so the calls are refused here instead.
 */
class AndroidApiLevelSourceTest {

    private val banned = Regex("""\.(removeFirst|removeLast)\(\)""")

    private val androidReachable = listOf("composeApp", "kobplayer", "kobfocus")
        .flatMap { module -> listOf("commonMain", "androidMain").map { File("../$module/src/$it") } }

    @Test
    fun `no JDK 21 list members in code that runs on Android`() {
        val roots = androidReachable.filter { it.isDirectory }
        check(roots.isNotEmpty()) { "no source roots found from ${File(".").absolutePath}" }
        val hits = roots.flatMap { root ->
            root.walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
                file.readLines().mapIndexedNotNull { i, line ->
                    "${file.relativeTo(File(".."))}:${i + 1}: ${line.trim()}".takeIf { banned.containsMatchIn(line) }
                }
            }
        }
        assertEquals(emptyList(), hits, "use removeAt(0) / removeAt(lastIndex)")
    }
}
