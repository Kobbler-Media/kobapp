package dev.kobbler.app.ui.player

import dev.kobbler.app.data.SessionFeed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StartLivenessTest {

    private fun feed(served: Long = 0, idle: Long? = 500, bps: Long? = null, downloaded: Long? = null, total: Long? = null) =
        SessionFeed(bytesServed = served, idleMs = idle, downloadBps = bps, downloadedBytes = downloaded, totalBytes = total)

    private data class Case(val name: String, val probe: StartProbe, val stalled: Boolean, val healthy: Boolean = false)

    private val direct = DIRECT_START_BUDGET_MS

    private val cases = listOf(
        Case("preparing is a wait however long", StartProbe(30_000, readyForMs = null, budgetMs = direct), stalled = false),
        Case("preparing still ends at the ceiling", StartProbe(START_CEILING_MS, readyForMs = null, budgetMs = direct), stalled = true),
        Case("no feed: inside the budget since ready", StartProbe(11_000, readyForMs = 5_500, budgetMs = direct), stalled = false),
        Case("no feed: budget spent since ready", StartProbe(12_000, readyForMs = direct, budgetMs = direct), stalled = true),
        Case("feed: bytes growing is healthy past the budget", StartProbe(20_000, 15_000, direct, feed(served = 9, idle = 50), servedGrew = true), stalled = false),
        Case("feed: a short idle is healthy past the budget", StartProbe(20_000, 15_000, direct, feed(served = 9, idle = 3_000)), stalled = false),
        Case("feed: idle past the limit is a stall", StartProbe(20_000, 15_000, direct, feed(served = 9, idle = FEED_IDLE_STALL_MS + 1)), stalled = true),
        Case("feed: no request yet, inside the direct budget", StartProbe(5_000, 5_000, TRANSCODE_START_BUDGET_MS, feed(idle = null)), stalled = false),
        Case("feed: no request within the direct budget", StartProbe(6_000, direct, TRANSCODE_START_BUDGET_MS, feed(idle = null)), stalled = true),
        Case("feed: stall while downloading", StartProbe(20_000, 15_000, direct, feed(idle = 11_000, bps = 30_000_000)), stalled = true, healthy = true),
        Case("feed: stall while the download grows", StartProbe(20_000, 15_000, direct, feed(idle = 11_000), downloadGrew = true), stalled = true, healthy = true),
        Case("feed: stall with the file complete", StartProbe(20_000, 15_000, direct, feed(idle = 11_000, downloaded = 10, total = 10)), stalled = true, healthy = true),
        Case("feed: stall with the download stuck", StartProbe(20_000, 15_000, direct, feed(idle = 11_000, bps = 0, downloaded = 5, total = 10)), stalled = true),
        Case("feed: still healthy at the ceiling is a stall", StartProbe(START_CEILING_MS, 40_000, direct, feed(served = 9, idle = 10), servedGrew = true), stalled = true),
    )

    @Test
    fun verdicts() = cases.forEach { c ->
        val verdict = judgeStart(c.probe)
        if (!c.stalled) {
            assertEquals(StartVerdict.Wait, verdict, c.name)
        } else {
            assertIs<StartVerdict.Stalled>(verdict, c.name)
            assertEquals(c.healthy, verdict.sourceHealthy, c.name)
        }
    }

    @Test
    fun feedLines() = listOf(
        null to null,
        feed() to null,
        feed(served = 24_000_000) to "Buffering · 24 MB",
        feed(served = 1_500_000_000) to "Buffering · 1.5 GB",
        feed(served = 300) to "Buffering · 1 KB",
        feed(bps = 3_000_000) to "Fetching the source · 3 MB/s",
    ).forEach { (f, line) -> assertEquals(line, startFeedLine(f), "$f") }
}
