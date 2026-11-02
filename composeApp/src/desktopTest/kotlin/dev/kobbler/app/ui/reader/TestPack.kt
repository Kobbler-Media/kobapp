package dev.kobbler.app.ui.reader

import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.ui.reader.pack.Block
import dev.kobbler.app.ui.reader.pack.BlockKind
import dev.kobbler.app.ui.reader.pack.ChapterEntry
import dev.kobbler.app.ui.reader.pack.FixedLayoutDto
import dev.kobbler.app.ui.reader.pack.ImageRef
import dev.kobbler.app.ui.reader.pack.Manifest
import dev.kobbler.app.ui.reader.pack.PackChapter
import dev.kobbler.app.ui.reader.pack.PackJson
import dev.kobbler.app.ui.reader.pack.Span

internal class TestChapter(
    val title: String? = null,
    val paragraphs: List<String> = emptyList(),
    val image: ImageRef? = null,
)

internal fun textChapter(title: String, vararg paragraphs: String) = TestChapter(title, paragraphs.toList())

internal fun pictureChapter(alt: String, width: Int = 600, height: Int = 900) =
    TestChapter(image = ImageRef("OEBPS/$alt.png", alt, width, height))

/** Stub-server routes answering the reader pack of a book made of [chapters]. */
internal fun packRoutes(
    bookId: String,
    chapters: List<TestChapter>,
    fixedLayout: Boolean = false,
    rtl: Boolean = false,
): Map<String, (HttpExchange) -> String> {
    val manifest = Manifest(
        generation = 1,
        title = "Test Book",
        rtl = rtl,
        chapters = chapters.mapIndexed { i, c ->
            ChapterEntry(
                href = "OEBPS/ch$i.xhtml",
                title = c.title,
                weight = 100,
                fixedLayout = if (fixedLayout) FixedLayoutDto(spreads = true) else null,
            )
        },
    )
    val base = "/api/v1/admin/books/$bookId/reader-pack"
    return buildMap {
        put(base) { _ -> PackJson.encodeToString(Manifest.serializer(), manifest) }
        chapters.forEachIndexed { i, chapter ->
            val blocks = chapter.image?.let { listOf(Block(kind = BlockKind.Image, image = it)) }
                ?: chapter.paragraphs.map { Block(kind = BlockKind.Text, spans = listOf(Span(it))) }
            put("$base/chapters/$i") { _ -> PackJson.encodeToString(PackChapter.serializer(), PackChapter(blocks)) }
        }
    }
}
