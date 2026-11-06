package dev.kobbler.app.ui.reader

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import dev.kobbler.app.ui.reader.layout.ChapterLayout
import dev.kobbler.app.ui.reader.layout.layoutChapter
import dev.kobbler.app.ui.reader.layout.layoutWindow
import dev.kobbler.app.ui.reader.pack.BlockOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/** A chapter to lay out, starting with the page holding [anchor], or the last page when null. */
internal class LayoutRequest(val view: ChapterView, val anchor: BlockOffset?)

private data class LayoutKey(val chapter: Int, val spec: LayoutSpec)

/**
 * The book's chapter layouts. The chapters asked for are laid out at once, the page they
 * open on first; once they are done the chapters around them follow, one at a time. Every
 * layout's page breaks are kept on disk, so a chapter met again only has to lay out the
 * pages on screen before it shows. All of it runs in [scope] and is touched only on its thread.
 */
@Stable
internal class ReaderLayouts(
    private val book: ReaderBook,
    private val scope: CoroutineScope,
    private val store: PageBreakStore = PageBreakStore(book.bookId),
) {
    private val shown = mutableStateMapOf<LayoutKey, ChapterLayout>()
    private val latest = mutableStateMapOf<Int, ChapterLayout>()
    private val jobs = HashMap<LayoutKey, Job>()
    private var ahead: Pair<LayoutKey, Job>? = null
    private var wanted: Set<LayoutKey> = emptySet()

    /** The chapter laid out for [spec]; failing that, its last layout on a page of the same size. */
    fun layoutOf(chapter: Int, spec: LayoutSpec): ChapterLayout? =
        shown[LayoutKey(chapter, spec)] ?: latest[chapter]?.takeIf { it.pageSize == spec.pageSize }

    /** Lays out [requests] for [spec], then the chapters around [focus] once it is known. */
    fun want(spec: LayoutSpec, focus: Int?, requests: List<LayoutRequest>) {
        if (!spec.drawable) return
        val requested = requests.associateBy { LayoutKey(it.view.chapterIndex, spec) }
        wanted = requested.keys
        jobs.keys.filter { it !in requested }.forEach { jobs.remove(it)?.cancel() }
        for ((key, request) in requested) {
            if (shown[key]?.complete == true || jobs[key]?.isActive == true) continue
            jobs[key] = scope.launch { lay(key, request) }
        }
        evict(spec, focus ?: requests.firstOrNull()?.view?.chapterIndex ?: 0)
        if (focus != null) readAround(LayoutKey(focus, spec))
    }

    private suspend fun lay(key: LayoutKey, request: LayoutRequest) {
        val spec = key.spec
        val view = request.view
        val saved = store.get(spec.fingerprint, key.chapter, view.contentKey)
        val windowed = saved?.let {
            val page = request.anchor?.let { at -> saved.pageOf(at.block, at.offset) } ?: saved.size - 1
            layoutWindow(
                view.blocks, book.manifest.styles, spec.typography, spec.pageSize, spec.density, spec.resolver, spec.direction,
                saved, page..page + 1,
            )
        }
        windowed?.let { publish(key, it) }
        val full = coroutineScope {
            val early = Channel<ChapterLayout>(Channel.CONFLATED)
            val relay = launch { for (layout in early) publish(key, layout) }
            try {
                layoutChapter(
                    view.blocks, book.manifest.styles, spec.typography, spec.pageSize, spec.density, spec.resolver, spec.direction,
                    early = request.anchor.takeIf { windowed == null },
                    onEarly = { early.trySend(it) },
                )
            } finally {
                early.close()
            }.also { relay.join() }
        }
        publish(key, full)
        if (full.breaks != saved) store.put(spec.fingerprint, key.chapter, view.contentKey, full.breaks)
    }

    private fun publish(key: LayoutKey, layout: ChapterLayout) {
        if (shown[key]?.complete == true) return
        shown[key] = layout
        latest[key.chapter] = layout
    }

    private fun readAround(center: LayoutKey) {
        if (ahead?.first == center) return
        ahead?.second?.cancel()
        ahead = center to scope.launch {
            awaitAsked()
            for (chapter in AroundOffsets.map { center.chapter + it }) {
                if (chapter !in 0..book.lastChapter) continue
                val key = LayoutKey(chapter, center.spec)
                if (shown[key]?.complete == true) continue
                val view = orNull { buildChapterView(book, chapter) } ?: continue
                if (view.page != null || store.get(key.spec.fingerprint, chapter, view.contentKey) != null) continue
                lay(key, LayoutRequest(view, BlockOffset()))
                evict(center.spec, center.chapter)
            }
        }
    }

    private suspend fun awaitAsked() {
        while (true) jobs.values.firstOrNull { it.isActive }?.join() ?: return
    }

    private fun evict(spec: LayoutSpec, focus: Int) {
        val spare = shown.keys.filter { it !in wanted }
            .sortedWith(compareBy<LayoutKey> { it.spec == spec }.thenByDescending { abs(it.chapter - focus) })
        spare.take((shown.size - Retained).coerceAtLeast(0)).forEach { shown.remove(it) }
        val near = (shown.keys.map { it.chapter } + wanted.map { it.chapter }).toSet()
        latest.keys.filter { it !in near }.forEach { latest.remove(it) }
    }

    private companion object {
        const val Retained = 5
        val AroundOffsets = listOf(2, -2, 3)
    }
}
