package dev.kobbler.app.ui.reader

/** Minimal stored (uncompressed) ZIP writer — just enough for [ZipArchive] to index and read. */
internal fun storedZip(vararg entries: Pair<String, ByteArray>): ByteArray {
    val out = ZipBytes()
    val central = ZipBytes()
    for ((name, data) in entries) {
        val nameBytes = name.encodeToByteArray()
        val crc = crc32(data)
        val offset = out.size
        out.addU32(0x04034B50) // local file header signature
        out.addU16(20)         // version needed
        out.addU16(0)          // flags
        out.addU16(0)          // method: stored
        out.addU16(0)          // mod time
        out.addU16(0x21)       // mod date (1980-01-01)
        out.addU32(crc)
        out.addU32(data.size)  // compressed size
        out.addU32(data.size)  // uncompressed size
        out.addU16(nameBytes.size)
        out.addU16(0)          // extra length
        out.add(nameBytes)
        out.add(data)

        central.addU32(0x02014B50) // central directory signature
        central.addU16(20)         // version made by
        central.addU16(20)         // version needed
        central.addU16(0)          // flags
        central.addU16(0)          // method
        central.addU16(0)          // mod time
        central.addU16(0x21)       // mod date
        central.addU32(crc)
        central.addU32(data.size)
        central.addU32(data.size)
        central.addU16(nameBytes.size)
        central.addU16(0)          // extra length
        central.addU16(0)          // comment length
        central.addU16(0)          // disk number
        central.addU16(0)          // internal attrs
        central.addU32(0)          // external attrs
        central.addU32(offset)     // local header offset
        central.add(nameBytes)
    }
    val cdOffset = out.size
    out.add(central.bytes())
    out.addU32(0x06054B50) // EOCD signature
    out.addU16(0)          // disk number
    out.addU16(0)          // disk with central directory
    out.addU16(entries.size)
    out.addU16(entries.size)
    out.addU32(central.size)
    out.addU32(cdOffset)
    out.addU16(0)          // comment length
    return out.bytes()
}

private fun crc32(data: ByteArray): Int {
    var crc = 0xFFFFFFFF.toInt()
    for (b in data) {
        var c = crc xor (b.toInt() and 0xFF)
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
        crc = c
    }
    return crc xor 0xFFFFFFFF.toInt()
}

private class ZipBytes {
    private var buf = ByteArray(0)
    val size: Int get() = buf.size
    fun add(data: ByteArray) {
        val grown = ByteArray(buf.size + data.size)
        buf.copyInto(grown)
        data.copyInto(grown, buf.size)
        buf = grown
    }
    fun addU16(v: Int) = add(byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte()))
    fun addU32(v: Int) = add(
        byteArrayOf(
            (v and 0xFF).toByte(),
            ((v ushr 8) and 0xFF).toByte(),
            ((v ushr 16) and 0xFF).toByte(),
            ((v ushr 24) and 0xFF).toByte(),
        ),
    )
    fun bytes(): ByteArray = buf
}
