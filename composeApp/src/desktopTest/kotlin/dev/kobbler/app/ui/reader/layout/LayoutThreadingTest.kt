package dev.kobbler.app.ui.reader.layout

import androidx.compose.ui.unit.IntSize
import dev.kobbler.app.ui.reader.layout.LayoutRig.prose
import dev.kobbler.app.ui.reader.pack.PackTextStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.measureTime

class LayoutThreadingTest {

    private val tenThousandWords = List(100) { prose(100, seed = it) }

    private suspend fun layOut() = layoutChapter(
        tenThousandWords, listOf(PackTextStyle()), LayoutRig.typography,
        IntSize(LayoutRig.Width, LayoutRig.pageHeightFor(20)), LayoutRig.density, LayoutRig.resolver,
    )

    @Test
    fun `concurrent layouts on the default dispatcher agree`() = runBlocking(Dispatchers.Default) {
        val results = List(4) { async { layOut() } }.awaitAll()
        val shapes = results.map { layout -> layout.pages.map { page -> page.slices.map { it.blockIndex to it.startOffset() } } }
        shapes.forEach { assertEquals(shapes.first(), it) }
    }

    @Test
    fun `ten thousand word chapter timing`() = runBlocking {
        layOut()
        val pages: Int
        val elapsed = measureTime { pages = layOut().pages.size }
        println("reader layout: 10k words -> $pages pages in $elapsed")
    }
}
