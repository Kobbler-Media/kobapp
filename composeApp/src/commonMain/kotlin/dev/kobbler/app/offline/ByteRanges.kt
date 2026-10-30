package dev.kobbler.app.offline

import kotlinx.serialization.Serializable

@Serializable
data class ByteRanges(private val starts: List<Long> = emptyList(), private val ends: List<Long> = emptyList()) {
    val spans: List<LongRange> get() = starts.indices.map { starts[it] until ends[it] }

    val filledBytes: Long get() = starts.indices.sumOf { ends[it] - starts[it] }

    operator fun plus(range: LongRange): ByteRanges {
        if (range.isEmpty()) return this
        var start = range.first
        var end = range.last + 1
        val newStarts = ArrayList<Long>(starts.size + 1)
        val newEnds = ArrayList<Long>(starts.size + 1)
        var placed = false
        for (i in starts.indices) {
            when {
                ends[i] < start -> { newStarts += starts[i]; newEnds += ends[i] }
                starts[i] > end -> {
                    if (!placed) { newStarts += start; newEnds += end; placed = true }
                    newStarts += starts[i]; newEnds += ends[i]
                }
                else -> { start = minOf(start, starts[i]); end = maxOf(end, ends[i]) }
            }
        }
        if (!placed) { newStarts += start; newEnds += end }
        return ByteRanges(newStarts, newEnds)
    }

    /** End (exclusive) of the filled run containing [offset], or null when [offset] is not on disk. */
    fun filledEndFrom(offset: Long): Long? {
        val i = starts.indices.firstOrNull { offset >= starts[it] && offset < ends[it] } ?: return null
        return ends[i]
    }

    /** Where the hole starting at [offset] stops: the next filled byte, or the end of the file. */
    fun gapEndFrom(offset: Long, size: Long): Long =
        starts.firstOrNull { it > offset }?.coerceAtMost(size) ?: size

    /** The first unfilled span at or after [from], wrapping to the head of the file once it runs out. */
    fun firstGap(from: Long, size: Long): LongRange? =
        gapAtOrAfter(from.coerceIn(0, size), size) ?: gapAtOrAfter(0, size)

    private fun gapAtOrAfter(from: Long, size: Long): LongRange? {
        var cursor = from
        while (cursor < size) {
            val filledEnd = filledEndFrom(cursor)
            if (filledEnd == null) return cursor until gapEndFrom(cursor, size)
            cursor = filledEnd
        }
        return null
    }

    fun covers(size: Long): Boolean = size > 0 && starts.size == 1 && starts[0] == 0L && ends[0] >= size
}
