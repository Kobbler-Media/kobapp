package dev.kobbler.app.ui.dashboard

import kotlin.test.Test
import kotlin.test.assertEquals

class LiveTvStartTabTest {
    @Test
    fun `the tab opens where the viewer will find something`() {
        data class Case(val name: String, val remembered: LiveTvTab?, val onNowFilled: Boolean?, val expected: LiveTvTab)
        listOf(
            Case("suggestions on hand open On now", null, true, LiveTvTab.OnNow),
            Case("still loading holds On now rather than flashing the guide", null, null, LiveTvTab.OnNow),
            Case("nothing to suggest falls back to the guide", null, false, LiveTvTab.Guide),
            Case("the last pick wins", LiveTvTab.Channels, true, LiveTvTab.Channels),
            Case("a remembered On now with nothing in it is the guide", LiveTvTab.OnNow, false, LiveTvTab.Guide),
        ).forEach { c -> assertEquals(c.expected, liveTvStartTab(c.remembered, c.onNowFilled), c.name) }
    }
}
