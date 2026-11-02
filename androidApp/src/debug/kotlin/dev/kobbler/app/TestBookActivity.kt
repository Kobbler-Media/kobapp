package dev.kobbler.app

import android.app.UiModeManager
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.kobbler.app.data.Book
import dev.kobbler.app.platform.FileCache
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.platform.Settings
import dev.kobbler.app.ui.reader.ReaderScreen
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

/**
 * Dev-only: opens the packaged test book straight in the reader, no server required.
 *
 * Build a reader pack from any EPUB with kob-ebook's example
 * (`cargo run --release --example reader_pack -- book.epub out/`) and copy its
 * `manifest.json` and `chapters/` to androidApp/src/debug/assets/testbook/pack/
 * (gitignored). The reader's pack requests are answered from there, so the real
 * fetch → layout → render path runs unchanged. Images and progress go to a dead
 * endpoint and degrade quietly.
 */
class TestBookActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Settings.init(this)
        FileCache.init(this)

        val packPrefix = "/api/v1/admin/books/test-sample/reader-pack"
        val client = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            val asset = when {
                path == packPrefix -> "testbook/pack/manifest.json"
                path.startsWith("$packPrefix/chapters/") -> "testbook/pack/chapters/${path.substringAfterLast('/')}.json"
                else -> null
            }
            val body = asset?.let { runCatching { assets.open(it).use { s -> s.readBytes() } }.getOrNull() }
            if (body == null) respond("", HttpStatusCode.NotFound)
            else respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        })
        val api = devBookApi(client)
        val book = Book(
            id = "test-sample",
            title = "Test book",
            author = "",
            coverUrl = "",
            formats = listOf("EPUB"),
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
