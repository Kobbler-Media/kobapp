package dev.kobbler.app.ui.reader

import androidx.compose.ui.unit.IntSize

internal fun imagePixelSize(bytes: ByteArray): IntSize? = when {
    bytes.startsWith(0x89, 'P'.code, 'N'.code, 'G'.code) -> sized(bytes.be32(16), bytes.be32(20))
    bytes.startsWith('G'.code, 'I'.code, 'F'.code) -> sized(bytes.le16(6), bytes.le16(8))
    bytes.startsWith(0xFF, 0xD8) -> jpegSize(bytes)
    bytes.startsWith('R'.code, 'I'.code, 'F'.code, 'F'.code) && bytes.ascii(8, 4) == "WEBP" -> webpSize(bytes)
    else -> null
}

private fun jpegSize(bytes: ByteArray): IntSize? {
    var i = 2
    while (i + 9 < bytes.size) {
        if (bytes.u8(i) != 0xFF) return null
        val marker = bytes.u8(i + 1)
        if (marker == 0xFF) { i++; continue }
        val isFrame = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
        if (isFrame) return sized(bytes.be16(i + 7), bytes.be16(i + 5))
        i += 2 + bytes.be16(i + 2)
    }
    return null
}

private fun webpSize(bytes: ByteArray): IntSize? = when (bytes.ascii(12, 4)) {
    "VP8 " -> sized(bytes.le16(26) and 0x3FFF, bytes.le16(28) and 0x3FFF)
    "VP8L" -> {
        val bits = bytes.le16(21) or (bytes.le16(23) shl 16)
        sized((bits and 0x3FFF) + 1, ((bits shr 14) and 0x3FFF) + 1)
    }
    "VP8X" -> sized(bytes.le24(24) + 1, bytes.le24(27) + 1)
    else -> null
}

private fun sized(width: Int, height: Int): IntSize? =
    if (width > 0 && height > 0) IntSize(width, height) else null

private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
    size >= prefix.size && prefix.indices.all { u8(it) == prefix[it] }

private fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF

private fun ByteArray.ascii(at: Int, length: Int): String? =
    if (at + length > size) null else decodeToString(at, at + length)

private fun ByteArray.be16(i: Int): Int = if (i + 2 > size) 0 else (u8(i) shl 8) or u8(i + 1)

private fun ByteArray.be32(i: Int): Int = if (i + 4 > size) 0 else (be16(i) shl 16) or be16(i + 2)

private fun ByteArray.le16(i: Int): Int = if (i + 2 > size) 0 else u8(i) or (u8(i + 1) shl 8)

private fun ByteArray.le24(i: Int): Int = if (i + 3 > size) 0 else le16(i) or (u8(i + 2) shl 16)
