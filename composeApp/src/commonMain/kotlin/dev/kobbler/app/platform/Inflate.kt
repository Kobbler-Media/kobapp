package dev.kobbler.app.platform

/**
 * Pure Kotlin implementation of raw DEFLATE (RFC 1951) decompression.
 * Used on platforms without java.util.zip (iOS, WASM).
 */
internal fun inflateRaw(compressed: ByteArray, expectedSize: Int = 0): ByteArray {
    val reader = BitReader(compressed)
    val output = InflateOutput(if (expectedSize > 0) expectedSize else compressed.size * 3)

    var isFinal = false
    while (!isFinal) {
        isFinal = reader.readBits(1) == 1
        when (reader.readBits(2)) {
            0 -> inflateStored(reader, output)
            1 -> inflateHuffman(reader, output, FIXED_LITLEN_TREE, FIXED_DIST_TREE)
            2 -> {
                val (litlenTree, distTree) = readDynamicTrees(reader)
                inflateHuffman(reader, output, litlenTree, distTree)
            }
            else -> throw IllegalStateException("Invalid DEFLATE block type")
        }
    }

    return output.toByteArray()
}

private fun inflateStored(reader: BitReader, output: InflateOutput) {
    reader.alignToByte()
    val len = reader.readBits(16)
    reader.readBits(16) // nlen
    for (i in 0 until len) {
        output.append(reader.readBits(8).toByte())
    }
}

private fun inflateHuffman(
    reader: BitReader,
    output: InflateOutput,
    litlenTree: HuffmanTree,
    distTree: HuffmanTree,
) {
    while (true) {
        val sym = litlenTree.decode(reader)
        when {
            sym < 256 -> output.append(sym.toByte())
            sym == 256 -> return
            else -> {
                val length = decodeLength(sym, reader)
                val distSym = distTree.decode(reader)
                val distance = decodeDistance(distSym, reader)
                output.copyBack(distance, length)
            }
        }
    }
}

// --- Length/distance tables (RFC 1951 Section 3.2.5) ---

private val LENGTH_BASE = intArrayOf(
    3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31,
    35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258,
)
private val LENGTH_EXTRA = intArrayOf(
    0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2,
    3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0,
)
private val DIST_BASE = intArrayOf(
    1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193,
    257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577,
)
private val DIST_EXTRA = intArrayOf(
    0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6,
    7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13,
)

private fun decodeLength(sym: Int, reader: BitReader): Int {
    val idx = sym - 257
    return LENGTH_BASE[idx] + reader.readBits(LENGTH_EXTRA[idx])
}

private fun decodeDistance(sym: Int, reader: BitReader): Int {
    return DIST_BASE[sym] + reader.readBits(DIST_EXTRA[sym])
}

// --- Huffman tree with two-level lookup table ---

private class HuffmanTree(codeLengths: IntArray, maxSymbol: Int = codeLengths.size) {
    // First-level table: 9-bit lookup. Packed entry = (symbol shl 8) | bitCount
    private val table: IntArray
    private val tableBits = 9
    private val tableSize = 1 shl tableBits
    // Second-level: sorted list of (reversed_code, symbol, bits) for codes > tableBits
    private val longCodes: List<LongCode>

    private data class LongCode(val code: Int, val symbol: Int, val bits: Int)

    init {
        table = IntArray(tableSize) { -1 }
        val longs = mutableListOf<LongCode>()

        if (codeLengths.isEmpty() || codeLengths.all { it == 0 }) {
            longCodes = emptyList()
        } else {
            val maxLen = codeLengths.max()
            val blCount = IntArray(maxLen + 1)
            for (i in 0 until minOf(maxSymbol, codeLengths.size)) {
                if (codeLengths[i] > 0) blCount[codeLengths[i]]++
            }

            val nextCode = IntArray(maxLen + 1)
            var code = 0
            for (bits in 1..maxLen) {
                code = (code + blCount[bits - 1]) shl 1
                nextCode[bits] = code
            }

            for (sym in 0 until minOf(maxSymbol, codeLengths.size)) {
                val len = codeLengths[sym]
                if (len == 0) continue
                val c = nextCode[len]++
                val reversed = reverseBits(c, len)

                if (len <= tableBits) {
                    val fill = 1 shl (tableBits - len)
                    for (j in 0 until fill) {
                        val idx = reversed or (j shl len)
                        if (idx < tableSize) {
                            table[idx] = (sym shl 8) or len
                        }
                    }
                } else {
                    longs.add(LongCode(reversed, sym, len))
                }
            }
            longCodes = longs
        }
    }

    fun decode(reader: BitReader): Int {
        val bits = reader.peekBits(tableBits)
        val entry = table[bits]
        if (entry >= 0) {
            reader.consumeBits(entry and 0xFF)
            return entry shr 8
        }

        // Long codes: try each, matching the first tableBits as prefix
        for (lc in longCodes) {
            val peeked = reader.peekBits(lc.bits)
            if (peeked == lc.code) {
                reader.consumeBits(lc.bits)
                return lc.symbol
            }
        }

        throw IllegalStateException("Invalid Huffman code in DEFLATE stream")
    }

    companion object {
        fun reverseBits(value: Int, numBits: Int): Int {
            var v = value; var result = 0
            for (i in 0 until numBits) {
                result = (result shl 1) or (v and 1)
                v = v shr 1
            }
            return result
        }
    }
}

private fun reverseBits(value: Int, numBits: Int): Int = HuffmanTree.reverseBits(value, numBits)

// --- Fixed Huffman trees ---

private val FIXED_LITLEN_TREE: HuffmanTree by lazy {
    val lengths = IntArray(288)
    for (i in 0..143) lengths[i] = 8
    for (i in 144..255) lengths[i] = 9
    for (i in 256..279) lengths[i] = 7
    for (i in 280..287) lengths[i] = 8
    HuffmanTree(lengths)
}

private val FIXED_DIST_TREE: HuffmanTree by lazy {
    HuffmanTree(IntArray(32) { 5 })
}

// --- Dynamic tree reading ---

private val CODELEN_ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)

private fun readDynamicTrees(reader: BitReader): Pair<HuffmanTree, HuffmanTree> {
    val hlit = reader.readBits(5) + 257
    val hdist = reader.readBits(5) + 1
    val hclen = reader.readBits(4) + 4

    val codelenLengths = IntArray(19)
    for (i in 0 until hclen) {
        codelenLengths[CODELEN_ORDER[i]] = reader.readBits(3)
    }
    val codelenTree = HuffmanTree(codelenLengths, 19)

    val allLengths = IntArray(hlit + hdist)
    var i = 0
    while (i < allLengths.size) {
        val sym = codelenTree.decode(reader)
        when {
            sym < 16 -> allLengths[i++] = sym
            sym == 16 -> {
                val count = reader.readBits(2) + 3
                val prev = if (i > 0) allLengths[i - 1] else 0
                repeat(count) { if (i < allLengths.size) allLengths[i++] = prev }
            }
            sym == 17 -> {
                val count = reader.readBits(3) + 3
                repeat(count) { if (i < allLengths.size) allLengths[i++] = 0 }
            }
            sym == 18 -> {
                val count = reader.readBits(7) + 11
                repeat(count) { if (i < allLengths.size) allLengths[i++] = 0 }
            }
        }
    }

    return Pair(
        HuffmanTree(allLengths.copyOfRange(0, hlit), hlit),
        HuffmanTree(allLengths.copyOfRange(hlit, hlit + hdist), hdist),
    )
}

// --- Bit reader ---

private class BitReader(private val data: ByteArray) {
    private var bytePos = 0
    private var bitBuffer = 0L
    private var bitsInBuffer = 0

    private fun ensureBits(n: Int) {
        while (bitsInBuffer < n && bytePos < data.size) {
            bitBuffer = bitBuffer or ((data[bytePos].toLong() and 0xFF) shl bitsInBuffer)
            bytePos++
            bitsInBuffer += 8
        }
    }

    fun readBits(n: Int): Int {
        if (n == 0) return 0
        ensureBits(n)
        val result = (bitBuffer and ((1L shl n) - 1)).toInt()
        bitBuffer = bitBuffer ushr n
        bitsInBuffer -= n
        return result
    }

    fun peekBits(n: Int): Int {
        ensureBits(n)
        return (bitBuffer and ((1L shl n) - 1)).toInt()
    }

    fun consumeBits(n: Int) {
        bitBuffer = bitBuffer ushr n
        bitsInBuffer -= n
    }

    fun alignToByte() {
        val skip = bitsInBuffer % 8
        if (skip > 0) {
            bitBuffer = bitBuffer ushr skip
            bitsInBuffer -= skip
        }
    }
}

// --- Output buffer with LZ77 back-reference support ---

private class InflateOutput(initialCapacity: Int = 256) {
    private var data = ByteArray(initialCapacity)
    private var size = 0

    fun append(b: Byte) {
        ensureCapacity(size + 1)
        data[size++] = b
    }

    fun copyBack(distance: Int, length: Int) {
        if (distance > size) throw IllegalStateException("Invalid back-reference distance $distance > $size")
        ensureCapacity(size + length)
        // Must copy byte-by-byte: length can exceed distance (run-length encoding)
        var srcPos = size - distance
        for (i in 0 until length) {
            data[size++] = data[srcPos++]
        }
    }

    fun toByteArray(): ByteArray = data.copyOfRange(0, size)

    private fun ensureCapacity(needed: Int) {
        if (needed <= data.size) return
        var newSize = data.size * 2
        while (newSize < needed) newSize *= 2
        data = data.copyOf(newSize)
    }
}
