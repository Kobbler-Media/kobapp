package dev.kobbler.app.data

private const val QUOTE = '"'.code.toByte()
private const val BACKSLASH = '\\'.code.toByte()
private const val OPEN_BRACE = '{'.code.toByte()
private const val CLOSE_BRACE = '}'.code.toByte()

/**
 * Cuts a byte stream into whole top-level JSON objects.
 *
 * Nothing in the TTS stream promises one object per line — a render that fails tails off
 * as `}{` with no separator at all — and an object runs to hundreds of kilobytes of
 * base64 audio, so it arrives across any number of socket reads. Framing is therefore on
 * brace depth rather than newlines.
 *
 * Tracked over bytes, not characters: every structural byte is ASCII and a UTF-8
 * continuation byte can never be mistaken for one, so a character split across two reads
 * costs nothing. Bytes between objects are discarded, which is what makes a truncated
 * tail cost only its own object.
 */
internal class JsonObjectFramer {
    private var buf = ByteArray(8 * 1024)
    private var len = 0
    private var scan = 0
    private var depth = 0
    private var start = -1
    private var inString = false
    private var escaped = false

    fun feed(chunk: ByteArray, count: Int): List<String> {
        append(chunk, count)
        var out: MutableList<String>? = null
        while (scan < len) {
            val c = buf[scan]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == BACKSLASH -> escaped = true
                    c == QUOTE -> inString = false
                }
                scan++
                continue
            }
            when (c) {
                QUOTE -> inString = true
                OPEN_BRACE -> {
                    if (depth == 0) start = scan
                    depth++
                }
                CLOSE_BRACE -> if (depth > 0) depth--
            }
            scan++
            if (depth == 0 && start >= 0) {
                val complete = buf.decodeToString(start, scan)
                (out ?: mutableListOf<String>().also { out = it }).add(complete)
                start = -1
                discardScanned()
            }
        }
        if (depth == 0 && start < 0) discardScanned()
        return out ?: emptyList()
    }

    private fun append(chunk: ByteArray, count: Int) {
        if (len + count > buf.size) {
            buf = buf.copyOf(maxOf(buf.size * 2, len + count))
        }
        chunk.copyInto(buf, len, 0, count)
        len += count
    }

    private fun discardScanned() {
        if (scan == 0) return
        buf.copyInto(buf, 0, scan, len)
        len -= scan
        scan = 0
    }
}
