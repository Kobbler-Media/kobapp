package dev.kobbler.app.ui.player

import dev.kobbler.app.ui.player.ThumbnailFetch.Changed
import dev.kobbler.app.ui.player.ThumbnailFetch.Failed
import dev.kobbler.app.ui.player.ThumbnailFetch.Gone
import dev.kobbler.app.ui.player.ThumbnailFetch.Unchanged
import kotlin.test.Test
import kotlin.test.assertEquals

class ThumbnailPollTest {

    private fun delays(live: Boolean, vararg fetches: ThumbnailFetch): List<Long?> {
        val poll = ThumbnailPoll(live)
        return fetches.map { poll.after(it) }
    }

    @Test
    fun `a title stops asking once its thumbnails have stopped growing`() {
        assertEquals(listOf(15_000L, 30_000L, null), delays(live = false, Changed, Unchanged, Unchanged))
    }

    @Test
    fun `a title still being analysed keeps the fast pace while it grows`() {
        assertEquals(listOf(15_000L, 15_000L, 15_000L), delays(live = false, Changed, Changed, Changed))
    }

    @Test
    fun `a failing fetch backs off rather than giving up`() {
        assertEquals(
            listOf(30_000L, 60_000L, 120_000L, 120_000L, 15_000L),
            delays(live = false, Failed, Failed, Failed, Failed, Changed),
        )
    }

    @Test
    fun `a channel never stops, but backs off while nothing new arrives`() {
        assertEquals(
            listOf(15_000L, 30_000L, 60_000L, 120_000L, 120_000L, 15_000L),
            delays(live = true, Changed, Unchanged, Unchanged, Unchanged, Unchanged, Changed),
        )
    }

    @Test
    fun `a session that is gone is never asked again, live or not`() {
        assertEquals(listOf(15_000L, null), delays(live = false, Changed, Gone))
        assertEquals(listOf(30_000L, null), delays(live = true, Failed, Gone))
    }
}
