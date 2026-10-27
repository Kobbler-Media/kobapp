package dev.kobbler.app.ui.reader

/** Header-only image files: enough bytes for a size sniffer, not for a decoder. */
internal object TestImages {
    fun png(width: Int, height: Int): ByteArray =
        bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R') +
            be32(width) + be32(height) + bytes(8, 6, 0, 0, 0)

    fun gif(width: Int, height: Int): ByteArray =
        "GIF89a".encodeToByteArray() + le16(width) + le16(height) + bytes(0, 0, 0)

    fun jpeg(width: Int, height: Int): ByteArray =
        bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1, 0, 1, 0, 0) +
            bytes(0xFF, 0xC0, 0, 17, 8) + be16(height) + be16(width) + bytes(3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1) +
            bytes(0xFF, 0xD9)

    fun webpLossy(width: Int, height: Int): ByteArray =
        riff("VP8 ", bytes(0x9D, 0x01, 0x2A).let { bytes(0, 0, 0) + it } + le16(width) + le16(height))

    fun webpLossless(width: Int, height: Int): ByteArray {
        val bits = (width - 1) or ((height - 1) shl 14)
        return riff("VP8L", bytes(0x2F) + le32(bits))
    }

    fun webpExtended(width: Int, height: Int): ByteArray =
        riff("VP8X", bytes(0, 0, 0, 0) + le24(width - 1) + le24(height - 1))

    private fun riff(chunk: String, payload: ByteArray): ByteArray =
        "RIFF".encodeToByteArray() + le32(payload.size + 12) + "WEBP".encodeToByteArray() +
            chunk.encodeToByteArray() + le32(payload.size) + payload

    private fun bytes(vararg values: Any): ByteArray = ByteArray(values.size) {
        when (val v = values[it]) {
            is Char -> v.code.toByte()
            is Int -> v.toByte()
            else -> error("unsupported $v")
        }
    }

    private fun be16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
    private fun be32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
    private fun le24(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte())
    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
}
