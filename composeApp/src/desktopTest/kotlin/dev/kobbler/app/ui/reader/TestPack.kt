package dev.kobbler.app.ui.reader

import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.ui.reader.pack.Block
import dev.kobbler.app.ui.reader.pack.BlockFloat
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
    val float: BlockFloat? = null,
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
            val text = chapter.paragraphs.map { Block(kind = BlockKind.Text, spans = listOf(Span(it))) }
            val picture = chapter.image?.let {
                Block(kind = BlockKind.Image, image = it, breakBefore = chapter.float == null && text.isNotEmpty(), float = chapter.float)
            }
            val blocks = if (chapter.float != null) listOfNotNull(picture) + text else text + listOfNotNull(picture)
            put("$base/chapters/$i") { _ -> PackJson.encodeToString(PackChapter.serializer(), PackChapter(blocks)) }
        }
    }
}
