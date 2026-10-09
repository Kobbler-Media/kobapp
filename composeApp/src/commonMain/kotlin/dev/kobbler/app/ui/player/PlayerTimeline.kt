package dev.kobbler.app.ui.player

/**
 * Maps the player's clock onto absolute media time.
 *
 * A transcoded session created with `start_time` is served a playlist whose first
 * segment is the resume point (`#EXT-X-MEDIA-SEQUENCE:0`), so the player's zero is
 * that point rather than the start of the title. Everything outside the player —
 * chapters, skip segments, thumbnail cues, saved progress, the OS media session —
 * speaks absolute media time, so it has to cross here and nowhere else.
 */
data class PlayerTimeline(val offsetMs: Long = 0L) {

    fun toAbsoluteMs(playerMs: Long): Long = playerMs + offsetMs

    fun toPlayerMs(absoluteMs: Long): Long = (absoluteMs - offsetMs).coerceAtLeast(0L)

    /** The player only knows about the part of the title it was handed. */
    fun absoluteDurationMs(playerDurationMs: Long): Long =
        if (playerDurationMs <= 0L) playerDurationMs else playerDurationMs + offsetMs

    companion object {
        val Direct = PlayerTimeline(0L)

        fun of(serverStartSeconds: Int?): PlayerTimeline {
            val seconds = serverStartSeconds ?: return Direct
            return if (seconds > 0) PlayerTimeline(seconds * 1000L) else Direct
        }
    }
}
