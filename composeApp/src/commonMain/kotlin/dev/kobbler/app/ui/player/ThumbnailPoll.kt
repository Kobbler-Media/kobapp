package dev.kobbler.app.ui.player

enum class ThumbnailFetch { Changed, Unchanged, Failed }

private const val BasePollMs = 15_000L
private const val MaxBackoffSteps = 3

class ThumbnailPoll(private val live: Boolean) {
    private var unchanged = 0
    private var idle = 0

    fun after(fetch: ThumbnailFetch): Long? {
        when (fetch) {
            ThumbnailFetch.Changed -> {
                unchanged = 0
                idle = 0
            }
            ThumbnailFetch.Unchanged -> {
                unchanged++
                idle++
            }
            ThumbnailFetch.Failed -> idle++
        }
        if (!live && unchanged >= 2) return null
        return BasePollMs shl idle.coerceAtMost(MaxBackoffSteps)
    }
}
