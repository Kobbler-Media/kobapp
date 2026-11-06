package dev.kobbler.app.ui.reader

import dev.kobbler.app.ui.reader.layout.PageBreaks
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PageBreakStoreTest {

    private class MemoryFiles : LayoutFiles {
        val files = mutableMapOf<String, ByteArray>()
        override fun read(key: String) = files[key]
        override fun write(key: String, bytes: ByteArray) { files[key] = bytes }
        override fun remove(key: String) { files.remove(key) }
    }

    private fun breaks(vararg starts: Int) = PageBreaks(starts)

    @Test
    fun `entries survive encoding`() {
        val entries = listOf(
            SavedBreaks("v1|1080x2200|ünïcode", 3, -123456789, breaks(0, 0, 0, 4, 812, 17, 9, 0, 3)),
            SavedBreaks("other", 0, Int.MAX_VALUE, breaks(0, 0, 0)),
        )
        val decoded = PageBreakCodec.decode(PageBreakCodec.encode(entries))
        assertEquals(entries.map { listOf(it.fingerprint, it.chapter, it.contentKey, it.breaks) }, decoded.map { listOf(it.fingerprint, it.chapter, it.contentKey, it.breaks) })
    }

    @Test
    fun `a file cut short or of another format reads as empty`() {
        val bytes = PageBreakCodec.encode(listOf(SavedBreaks("f", 1, 2, breaks(0, 0, 0, 5, 10, 2))))
        for (end in 0 until bytes.size) assertTrue(PageBreakCodec.decode(bytes.copyOf(end)).isEmpty(), "cut at $end")
        assertTrue(PageBreakCodec.decode(byteArrayOf(9, 9, 9)).isEmpty())
    }

    @Test
    fun `breaks saved before text could wrap around a float read as empty`() {
        val formatOne = byteArrayOf(2, 2, 2, 'f'.code.toByte(), 2, 4, 6, 0, 0, 0)
        assertTrue(PageBreakCodec.decode(formatOne).isEmpty())
    }

    @Test
    fun `breaks come back only for the same setup and the same chapter content`() = runTest {
        val files = MemoryFiles()
        PageBreakStore("b", files).put("f", 2, 77, breaks(0, 0, 0, 3, 40, 2))
        val reopened = PageBreakStore("b", files)
        assertEquals(breaks(0, 0, 0, 3, 40, 2), reopened.get("f", 2, 77))
        assertNull(reopened.get("f", 2, 78))
        assertNull(reopened.get("g", 2, 77))
        assertNull(reopened.get("f", 1, 77))
    }

    @Test
    fun `only the latest page setups of a book are kept`() = runTest {
        val files = MemoryFiles()
        val store = PageBreakStore("b", files)
        listOf("small", "medium", "large").forEach { store.put(it, 0, 1, breaks(0, 0, 0)) }
        val reopened = PageBreakStore("b", files)
        assertNull(reopened.get("small", 0, 1))
        assertEquals(breaks(0, 0, 0), reopened.get("medium", 0, 1))
        assertEquals(breaks(0, 0, 0), reopened.get("large", 0, 1))
    }

    @Test
    fun `only the books opened last keep their breaks`() = runTest {
        val files = MemoryFiles()
        repeat(PageBreakStore.Books + 3) { PageBreakStore("book$it", files).put("f", 0, 1, breaks(0, 0, 0)) }
        val kept = files.files.keys.filter { it.startsWith("page-breaks-book") }.toSet()
        assertEquals((3 until PageBreakStore.Books + 3).map { PageBreakStore.fileKey("book$it") }.toSet(), kept)
    }
}
