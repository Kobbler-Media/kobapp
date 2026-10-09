package dev.kobbler.app

import android.app.UiModeManager
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.kobbler.app.data.Book
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FileCache
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.platform.Settings
import dev.kobbler.app.ui.reader.ReaderScreen
import java.io.File

/**
 * Dev-only: opens the packaged test book straight in the reader, no server required.
 *
 * Drop any KEPUB at androidApp/src/debug/assets/testbook/sample.kepub.epub (gitignored).
 * It is staged at cache/books/epub_test-sample — the exact key fetchBookFile
 * checks first — so the real reader path runs unchanged: cache hit, ZipArchive from
 * disk, parse, render. The API points at a dead endpoint; the reader only uses it
 * for progress sync, which degrades quietly (readerProgressOrNull is quiet, the
 * save on exit is fire-and-forget).
 */
class TestBookActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Settings.init(this)
        FileCache.init(this)

        val cacheKey = "epub_test-sample"
        val staged = File(cacheDir, "books/$cacheKey")
        if (!staged.exists()) {
            staged.parentFile?.mkdirs()
            assets.open("testbook/sample.kepub.epub").use { input ->
                staged.outputStream().use { input.copyTo(it) }
            }
        }

        val api = offlineTestApi()
        val book = Book(
            id = "test-sample",
            title = "Test book",
            author = "",
            coverUrl = "",
            formats = listOf("KEPUB"),
        )
        val isTv = getSystemService(UiModeManager::class.java)
            ?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        setContent {
            ReaderScreen(
                book = book,
                api = api,
                formFactor = if (isTv) FormFactor.TV else FormFactor.Phone,
                onBack = { finish() },
            )
        }
    }
}