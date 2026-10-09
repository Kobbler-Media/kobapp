package dev.kobbler.app.ui.player

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The player chrome must not anchor itself to the IME.
 *
 * `safeDrawing` folds in the keyboard, and the transport is anchored to the bottom edge,
 * so an IME inset lands on it whole. A keyboard opened somewhere else entirely — the
 * guide's search field, the PIN pad on the way in — leaves an inset behind, and the
 * transport then sits a fifth of the way up a 1080p screen with the film playing under
 * it. Measured at 230px on a Chromecast HD.
 *
 * Asserted against the source because there is nowhere else to assert it: window insets
 * are always zero on the desktop target these tests run on, so the layout cannot be made
 * to reproduce it. `SafeInsets.kt` states the same rule for the app's own chrome; the
 * player lives in a separate module that cannot see it.
 */
class PlayerChromeInsetsTest {

    private val playerSources = listOf(
        "kobplayer/src/commonMain/kotlin/dev/kobbler/kobplayer/ui/VideoPlayerOverlay.kt",
        "composeApp/src/commonMain/kotlin/dev/kobbler/app/ui/player/FullscreenVideoPlayer.kt",
    )

    /** Gradle runs tests from the module dir on some invocations and the root on others. */
    private fun resolve(path: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, path)
            if (candidate.exists()) return candidate
            dir = dir.parentFile
        }
        return File(path)
    }

    @Test
    fun `player chrome keeps clear of the bars and the cutout, not the keyboard`() {
        playerSources.forEach { path ->
            val file = resolve(path)
            assertTrue(file.exists(), "${file.absolutePath} is not where this test expects it")
            // Usage, not the word: the rule is worth naming in a comment where it applies.
            val used = file.readLines().filterNot { it.trimStart().startsWith("*") }
                .any { "safeDrawingPadding(" in it || "WindowInsets.safeDrawing" in it }
            assertTrue(
                !used,
                "${file.name} anchors player chrome to safeDrawing, which includes the IME",
            )
        }
    }
}
