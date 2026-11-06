package dev.kobbler.app.ui.reader

import dev.kobbler.app.ui.reader.layout.PageBreaks

internal class SavedBreaks(val fingerprint: String, val chapter: Int, val contentKey: Int, val breaks: PageBreaks)

internal object PageBreakCodec {
    private const val Format = 2

    fun encode(entries: List<SavedBreaks>): ByteArray {
        val out = VarintWriter()
        out.int(Format)
        out.int(entries.size)
        for (entry in entries) {
            out.string(entry.fingerprint)
            out.int(entry.chapter)
            out.int(entry.contentKey)
            val starts = entry.breaks.toIntArray()
            out.int(starts.size)
            starts.forEach(out::int)
        }
        return out.toByteArray()
    }

    /** Empty for anything unreadable: a file cut short, or written by another format. */
    fun decode(bytes: ByteArray): List<SavedBreaks> = try {
        val input = VarintReader(bytes)
        if (input.int() != Format) emptyList()
        else List(input.int().also { require(it in 0..bytes.size) }) {
            val fingerprint = input.string()
            val chapter = input.int()
            val contentKey = input.int()
            val count = input.int()
            require(count in 0..bytes.size)
            SavedBreaks(fingerprint, chapter, contentKey, PageBreaks(IntArray(count) { input.int() }))
        }
    } catch (_: IndexOutOfBoundsException) {
        emptyList()
    } catch (_: IllegalArgumentException) {
        emptyList()
    }
}

private class VarintWriter {
    private var buf = ByteArray(256)
    private var size = 0

    private fun byte(b: Int) {
        if (size == buf.size) buf = buf.copyOf(buf.size * 2)
        buf[size++] = b.toByte()
    }

    fun int(value: Int) {
        var v = (value shl 1) xor (value shr 31)
        while (v and 0x7F.inv() != 0) {
            byte((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        byte(v)
    }

    fun string(value: String) {
        val bytes = value.encodeToByteArray()
        int(bytes.size)
        bytes.forEach { byte(it.toInt()) }
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}

private class VarintReader(private val bytes: ByteArray) {
    private var at = 0

    fun int(): Int {
        var v = 0
        var shift = 0
        while (true) {
            val b = bytes[at++].toInt()
            v = v or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            require(shift < 35)
        }
        return (v ushr 1) xor -(v and 1)
    }

    fun string(): String {
        val length = int()
        require(length >= 0)
        return bytes.copyOfRange(at, at + length).decodeToString().also { at += length }
    }
}
