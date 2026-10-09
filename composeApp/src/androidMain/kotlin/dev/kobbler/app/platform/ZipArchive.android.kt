package dev.kobbler.app.platform

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

private const val EOCD_SIG = 0x06054b50L
private const val EOCD64_SIG = 0x06064b50L
private const val EOCD64_LOCATOR_SIG = 0x07064b50L
private const val CENTRAL_SIG = 0x02014b50L
private const val LOCAL_SIG = 0x04034b50L
private const val U32_MAX = 0xFFFFFFFFL
private const val METHOD_STORED = 0
private const val METHOD_DEFLATE = 8
private const val MAX_EOCD_SCAN = 65557

private class MemoryZip(private val data: ByteArray) {
    private class Entry(
        val name: String,
        val compressedSize: Int,
        val uncompressedSize: Int,
        val localHeaderOffset: Int,
        val method: Int,
    )

    private val index: List<Entry> by lazy { parseCentralDirectory() }
    private val names: List<String> by lazy { index.map { it.name } }
    private val byName: Map<String, Entry> by lazy {
        LinkedHashMap<String, Entry>(index.size).apply {
            for (e in index) if (!containsKey(e.name)) put(e.name, e)
        }
    }

    fun entries(): List<String> = names

    fun readText(path: String): String? = readBytes(path)?.decodeToString()

    fun readBytes(path: String): ByteArray? = byName[path]?.let(::extract)

    fun close() {}

    private fun u16(offset: Int): Int {
        if (offset < 0 || offset + 1 >= data.size) return -1
        return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun u32(offset: Int): Long {
        if (offset < 0 || offset + 3 >= data.size) return -1
        return (data[offset].toLong() and 0xFF) or
            ((data[offset + 1].toLong() and 0xFF) shl 8) or
            ((data[offset + 2].toLong() and 0xFF) shl 16) or
            ((data[offset + 3].toLong() and 0xFF) shl 24)
    }

    private fun u64(offset: Int): Long {
        if (offset < 0 || offset + 7 >= data.size) return -1
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (data[offset + i].toLong() and 0xFF)
        return v
    }

    /** The EOCD signature can occur inside stored file data, so a candidate only counts if
     *  the central directory it points at actually starts with a central header. */
    private fun findEocd(): Int {
        val floor = maxOf(0, data.size - MAX_EOCD_SCAN)
        for (i in data.size - 22 downTo floor) {
            if (u32(i) != EOCD_SIG) continue
            val count = u16(i + 10)
            val offset = u32(i + 16)
            if (count == 0 || count == 0xFFFF || offset == U32_MAX) return i
            if (offset in 0..Int.MAX_VALUE.toLong() && u32(offset.toInt()) == CENTRAL_SIG) return i
        }
        return -1
    }

    private fun parseCentralDirectory(): List<Entry> {
        if (data.size < 22) return emptyList()
        val eocd = findEocd()
        if (eocd < 0) return emptyList()

        var cdOffset = u32(eocd + 16)
        // 0xFFFF entries / 0xFFFFFFFF offset are the Zip64 sentinels; the real values live in
        // the Zip64 EOCD record the locator immediately before the EOCD points at.
        if (u16(eocd + 10) == 0xFFFF || cdOffset == U32_MAX) {
            val zip64Offset = zip64CentralDirOffset(eocd)
            if (zip64Offset >= 0) cdOffset = zip64Offset
            else if (cdOffset == U32_MAX) return emptyList()
        }
        if (cdOffset < 0 || cdOffset > Int.MAX_VALUE) return emptyList()

        val result = ArrayList<Entry>()
        var pos = cdOffset.toInt()
        while (pos + 46 <= data.size && u32(pos) == CENTRAL_SIG) {
            val method = u16(pos + 10)
            val nameLen = u16(pos + 28)
            val extraLen = u16(pos + 30)
            val commentLen = u16(pos + 32)
            if (nameLen < 0 || extraLen < 0 || commentLen < 0) break
            val nameStart = pos + 46
            val next = nameStart + nameLen + extraLen + commentLen
            if (next > data.size) break

            val sizes = Zip64Fields(u32(pos + 20), u32(pos + 24), u32(pos + 42))
            val usable = !sizes.needsZip64() || readZip64Extra(nameStart + nameLen, extraLen, sizes)
            if (usable && sizes.fitsInt()) {
                val name = data.decodeToString(nameStart, nameStart + nameLen)
                if (!name.endsWith("/")) {
                    result.add(
                        Entry(
                            name = name,
                            compressedSize = sizes.compressed.toInt(),
                            uncompressedSize = sizes.uncompressed.toInt(),
                            localHeaderOffset = sizes.localOffset.toInt(),
                            method = method,
                        )
                    )
                }
            }
            pos = next
        }
        return result
    }

    private fun zip64CentralDirOffset(eocd: Int): Long {
        val locator = eocd - 20
        if (locator < 0 || u32(locator) != EOCD64_LOCATOR_SIG) return -1
        val record = u64(locator + 8)
        if (record < 0 || record > Int.MAX_VALUE) return -1
        if (u32(record.toInt()) != EOCD64_SIG) return -1
        return u64(record.toInt() + 48)
    }

    private class Zip64Fields(var compressed: Long, var uncompressed: Long, var localOffset: Long) {
        fun needsZip64() =
            compressed == U32_MAX || uncompressed == U32_MAX || localOffset == U32_MAX

        fun fitsInt() = compressed in 0..Int.MAX_VALUE.toLong() &&
            uncompressed in 0..Int.MAX_VALUE.toLong() &&
            localOffset in 0..Int.MAX_VALUE.toLong()
    }

    /** Zip64 extended information field (0x0001): only the members that carry the 0xFFFFFFFF
     *  sentinel are present, in the fixed order uncompressed, compressed, local offset. */
    private fun readZip64Extra(extraStart: Int, extraLen: Int, fields: Zip64Fields): Boolean {
        var p = extraStart
        val end = extraStart + extraLen
        while (p + 4 <= end) {
            val id = u16(p)
            val len = u16(p + 2)
            if (id < 0 || len < 0 || p + 4 + len > end) return false
            if (id == 0x0001) {
                var q = p + 4
                val body = p + 4 + len
                if (fields.uncompressed == U32_MAX) {
                    if (q + 8 > body) return false
                    fields.uncompressed = u64(q); q += 8
                }
                if (fields.compressed == U32_MAX) {
                    if (q + 8 > body) return false
                    fields.compressed = u64(q); q += 8
                }
                if (fields.localOffset == U32_MAX) {
                    if (q + 8 > body) return false
                    fields.localOffset = u64(q)
                }
                return true
            }
            p += 4 + len
        }
        return false
    }

    private fun extract(entry: Entry): ByteArray? {
        val local = entry.localHeaderOffset
        if (local + 30 > data.size || u32(local) != LOCAL_SIG) return null
        // The local header's name/extra lengths are allowed to differ from the central
        // directory's, so the payload offset must come from the local copy.
        val nameLen = u16(local + 26)
        val extraLen = u16(local + 28)
        if (nameLen < 0 || extraLen < 0) return null
        val start = local + 30 + nameLen + extraLen
        if (start < 0 || start + entry.compressedSize > data.size) return null

        return when (entry.method) {
            METHOD_STORED ->
                if (start + entry.uncompressedSize > data.size) null
                else data.copyOfRange(start, start + entry.uncompressedSize)
            METHOD_DEFLATE -> inflate(start, entry.compressedSize, entry.uncompressedSize)
            else -> null
        }
    }

    private fun inflate(offset: Int, compressedSize: Int, expectedSize: Int): ByteArray? {
        val inflater = Inflater(true)
        try {
            inflater.setInput(data, offset, compressedSize)
            if (expectedSize > 0) {
                val out = ByteArray(expectedSize)
                var written = 0
                while (written < expectedSize && !inflater.finished()) {
                    val n = inflater.inflate(out, written, expectedSize - written)
                    if (n == 0) break
                    written += n
                }
                return if (written == expectedSize) out else null
            }
            val sink = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0) break
                sink.write(buf, 0, n)
            }
            return sink.toByteArray()
        } catch (_: DataFormatException) {
            return null
        } finally {
            inflater.end()
        }
    }
}

/**
 * Disk-backed when opened via [openZipArchive]: java.util.zip reads the central directory
 * and inflates one entry at a time, so a 100MB book never exists as an array in the heap.
 * The byte constructor stays for callers that already hold the archive in memory.
 */
actual class ZipArchive {
    private val memory: MemoryZip?
    private val file: java.util.zip.ZipFile?

    actual constructor(data: ByteArray) {
        memory = MemoryZip(data); file = null
    }

    internal constructor(zipFile: java.util.zip.ZipFile) {
        memory = null; file = zipFile
    }

    actual fun entries(): List<String> = memory?.entries()
        ?: file!!.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList()

    actual fun readBytes(path: String): ByteArray? = memory?.readBytes(path)
        ?: file!!.getEntry(path)?.let { entry -> file.getInputStream(entry).use { it.readBytes() } }

    actual fun readText(path: String): String? = readBytes(path)?.decodeToString()

    actual fun close() {
        memory?.close()
        runCatching { file?.close() }
    }
}

actual fun openZipArchive(path: String): ZipArchive? =
    runCatching { ZipArchive(java.util.zip.ZipFile(java.io.File(path))) }.getOrNull()
