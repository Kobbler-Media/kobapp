package dev.kobbler.app.platform

actual class ZipArchive actual constructor(private val data: ByteArray) {
    private val entries_: List<ZipEntry> by lazy { parseCentralDirectory() }

    actual fun entries(): List<String> = entries_.map { it.name }

    actual fun readText(path: String): String? = readBytes(path)?.decodeToString()

    actual fun readBytes(path: String): ByteArray? {
        val entry = entries_.find { it.name == path } ?: return null
        return extractEntry(entry)
    }

    actual fun close() {}

    private data class ZipEntry(
        val name: String,
        val compressedSize: Int,
        val uncompressedSize: Int,
        val localHeaderOffset: Int,
        val compressionMethod: Int,
    )

    private fun readUShort(offset: Int): Int {
        if (offset + 1 >= data.size) return 0
        return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readUInt(offset: Int): Int {
        if (offset + 3 >= data.size) return 0
        return (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8) or
                ((data[offset + 2].toInt() and 0xFF) shl 16) or
                ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun parseCentralDirectory(): List<ZipEntry> {
        if (data.size < 22) return emptyList()
        var eocdOffset = -1
        for (i in data.size - 22 downTo maxOf(0, data.size - 65557)) {
            if (readUInt(i) == 0x06054b50) {
                eocdOffset = i
                break
            }
        }
        if (eocdOffset < 0) return emptyList()

        val cdOffset = readUInt(eocdOffset + 16)
        val cdEntries = readUShort(eocdOffset + 10)
        val result = mutableListOf<ZipEntry>()
        var pos = cdOffset

        repeat(cdEntries) {
            if (pos + 46 > data.size || readUInt(pos) != 0x02014b50) return result
            val method = readUShort(pos + 10)
            val compSize = readUInt(pos + 20)
            val uncompSize = readUInt(pos + 24)
            val nameLen = readUShort(pos + 28)
            val extraLen = readUShort(pos + 30)
            val commentLen = readUShort(pos + 32)
            val localOffset = readUInt(pos + 42)
            if (pos + 46 + nameLen > data.size) return result
            val name = data.decodeToString(pos + 46, pos + 46 + nameLen)
            if (!name.endsWith("/")) {
                result.add(ZipEntry(name, compSize, uncompSize, localOffset, method))
            }
            pos += 46 + nameLen + extraLen + commentLen
        }
        return result
    }

    private fun extractEntry(entry: ZipEntry): ByteArray? {
        val localPos = entry.localHeaderOffset
        if (localPos + 30 > data.size || readUInt(localPos) != 0x04034b50) return null
        val nameLen = readUShort(localPos + 26)
        val extraLen = readUShort(localPos + 28)
        val dataOffset = localPos + 30 + nameLen + extraLen
        if (dataOffset + entry.compressedSize > data.size) return null

        return when (entry.compressionMethod) {
            0 -> data.copyOfRange(dataOffset, dataOffset + entry.uncompressedSize)
            8 -> {
                val compressed = data.copyOfRange(dataOffset, dataOffset + entry.compressedSize)
                try {
                    inflateRaw(compressed, entry.uncompressedSize)
                } catch (_: Exception) {
                    null
                }
            }
            else -> null
        }
    }
}

// No filesystem in the browser.
actual fun openZipArchive(path: String): ZipArchive? = null
