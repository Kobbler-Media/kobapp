package dev.kobbler.app.ui.reader

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.kobbler.app.UiTimeoutMs
import dev.kobbler.app.data.UserSettings
import kotlin.test.Test

/** Dimming the page darkens it and nothing else: every control under the shade still takes a touch. */
@OptIn(ExperimentalTestApi::class)
class ReaderDimTest {

    @Test
    fun `a dimmed reader still answers touches, including the setting that undims it`() {
        val keys = listOf("reader_brightness", "reader_theme")
        val before = keys.associateWith(UserSettings::getString)
        UserSettings.putString("reader_brightness", "0.5")
        try {
            readerTest(
                progress = """{"progress_percent":0}""",
                open = mutableStateOf(true),
                chapters = listOf(textChapter("Only", "Paragraph under the shade.")),
                width = 800,
                height = 900,
                slowChapter = null,
            ) { _, _ ->
                onNodeWithContentDescription("Settings").performClick()
                assertShown("Reader Settings")
                val other = if (UserSettings.getString("reader_theme") == "Light") "Sepia" else "Light"
                onNodeWithText(other).performClick()
                waitUntil(timeoutMillis = UiTimeoutMs) { UserSettings.getString("reader_theme") == other }
            }
        } finally {
            before.forEach { (key, value) -> if (value == null) UserSettings.remove(key) else UserSettings.putString(key, value) }
        }
    }
}
