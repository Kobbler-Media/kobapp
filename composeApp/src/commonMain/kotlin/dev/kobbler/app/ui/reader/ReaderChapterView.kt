package dev.kobbler.app.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.kobbler.app.ui.reader.epub.ChapterTtsView
import dev.kobbler.app.ui.reader.epub.XhtmlBlock
import dev.kobbler.app.ui.reader.epub.buildChapterTtsView
import dev.kobbler.app.ui.reader.epub.parseXhtmlToBlocks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Everything a chapter needs to render, built off the main thread: parsing the XHTML
 *  (twice — a render pass that drops hidden content and a TTS pass that keeps it),
 *  deriving the TTS word table and inflating the referenced images all read the ZIP
 *  and are far too slow for the composition pass. */
@Immutable
internal class ChapterView(
    val chapterIndex: Int,
    val blocks: List<XhtmlBlock>,
    val tts: ChapterTtsView,
    val images: Map<String, ByteArray>,
    val page: FittedPage?,
)

@Composable
internal fun rememberChapterView(epub: EpubBook, chapterIndex: Int): ChapterView? {
    var view by remember(epub) { mutableStateOf<ChapterView?>(null) }
    LaunchedEffect(epub, chapterIndex) {
        view = null
        view = buildChapterView(epub, chapterIndex)
    }
    return view?.takeIf { it.chapterIndex == chapterIndex }
}

internal suspend fun buildChapterView(epub: EpubBook, chapterIndex: Int): ChapterView? =
    withContext(Dispatchers.Default) {
        val chapter = epub.chapters.getOrNull(chapterIndex) ?: return@withContext null
        val blocks = parseXhtmlToBlocks(chapter.xhtml) { href -> resolveCssPath(href, chapter.href, epub) }
        val images = buildMap {
            for (src in imageSources(blocks)) {
                resolveImagePath(src, chapter.href, epub)?.let { put(src, it) }
            }
        }
        ChapterView(
            chapterIndex = chapterIndex,
            blocks = blocks,
            // TTS must read the hidden overlay text — that is the whole content of a
            // fixed-layout page — so it parses with hidden included.
            tts = buildChapterTtsView(
                parseXhtmlToBlocks(chapter.xhtml, includeHidden = true) { href ->
                    resolveCssPath(href, chapter.href, epub)
                },
            ),
            images = images,
            page = fittedPageOf(blocks, images, fixedLayout = chapter.fixedLayout != null),
        )
    }

/** Falls back to spine order immediately, then swaps in the NCX table of contents — which
 *  costs three more ZIP reads plus an XML parse — once it has been built off-thread. */
@Composable
internal fun rememberTocEntries(epub: EpubBook): List<TocEntry> {
    val fallback = remember(epub) {
        epub.chapters.mapIndexed { idx, ch -> TocEntry(ch.title ?: "Chapter ${idx + 1}", idx) }
    }
    var entries by remember(epub) { mutableStateOf(fallback) }
    LaunchedEffect(epub) {
        withContext(Dispatchers.Default) { extractToc(epub) }
            .takeIf { it.isNotEmpty() }
            ?.let { entries = it }
    }
    return entries
}

internal suspend fun chapterPlainText(epub: EpubBook, chapterIndex: Int): String =
    withContext(Dispatchers.Default) {
        val chapter = epub.chapters.getOrNull(chapterIndex) ?: return@withContext ""
        buildChapterTtsView(parseXhtmlToBlocks(chapter.xhtml, includeHidden = true)).plainText
    }

private fun imageSources(blocks: List<XhtmlBlock>): Set<String> = buildSet {
    fun walk(list: List<XhtmlBlock>) {
        for (block in list) when (block) {
            is XhtmlBlock.Image -> if (block.src.isNotBlank()) add(block.src)
            is XhtmlBlock.FixedPage -> if (block.image.src.isNotBlank()) add(block.image.src)
            is XhtmlBlock.BlockQuote -> walk(block.blocks)
            else -> {}
        }
    }
    walk(blocks)
}
