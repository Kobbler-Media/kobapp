package dev.kobbler.app.watchnext

import kotlin.test.Test
import kotlin.test.assertEquals

class WatchNextSyncPlanTest {
    private fun spec(id: String, engagedAt: Long = 1_000L, positionMs: Long? = 60_000L) = WatchNextProgramSpec(
        internalId = id,
        kind = WatchNextKind.Episode,
        reason = WatchNextReason.Continue,
        title = "Show $id",
        episodeTitle = null,
        season = 1,
        episode = 1,
        description = null,
        artUrl = null,
        artIsLandscape = true,
        durationMs = 1_200_000L,
        positionMs = positionMs,
        lastEngagementMs = engagedAt,
        launch = WatchNextLaunch("p1", "/tv/episodes/$id", "Show $id", null),
    )

    private fun published(rowId: Long, of: WatchNextProgramSpec, browsable: Boolean = true, fingerprint: String? = of.fingerprint) =
        PublishedProgram(rowId, of.internalId, fingerprint, browsable, of.lastEngagementMs)

    @Test
    fun emptyRowGetsEverythingInserted() {
        val a = spec("a"); val b = spec("b")
        assertEquals(WatchNextSyncPlan(inserts = listOf(a, b)), planWatchNextSync(emptyList(), listOf(a, b)))
    }

    @Test
    fun unchangedProgramsAreLeftAlone() {
        val a = spec("a")
        assertEquals(WatchNextSyncPlan(), planWatchNextSync(listOf(published(1, a)), listOf(a)))
    }

    @Test
    fun progressChangeUpdatesInPlace() {
        val old = spec("a", positionMs = 60_000L)
        val new = spec("a", engagedAt = 2_000L, positionMs = 120_000L)
        assertEquals(WatchNextSyncPlan(updates = listOf(7L to new)), planWatchNextSync(listOf(published(7, old)), listOf(new)))
    }

    @Test
    fun finishedOrRemovedItemsAreDeleted() {
        val gone = spec("gone"); val kept = spec("kept")
        assertEquals(
            WatchNextSyncPlan(deletes = listOf(1L)),
            planWatchNextSync(listOf(published(1, gone), published(2, kept)), listOf(kept)),
        )
    }

    @Test
    fun noProfileClearsTheRow() {
        assertEquals(
            WatchNextSyncPlan(deletes = listOf(1L, 2L)),
            planWatchNextSync(listOf(published(1, spec("a")), published(2, spec("b"))), emptyList()),
        )
    }

    @Test
    fun anotherProfilesProgramsAreReplaced() {
        val theirs = spec("p2/episode/a")
        val ours = spec("p1/episode/a")
        assertEquals(
            WatchNextSyncPlan(inserts = listOf(ours), deletes = listOf(3L)),
            planWatchNextSync(listOf(published(3, theirs)), listOf(ours)),
        )
    }

    @Test
    fun aProgramTheViewerDismissedStaysDismissedUntilTheyWatchItAgain() {
        val a = spec("a", engagedAt = 1_000L)
        val dismissed = published(4, a, browsable = false)
        assertEquals(WatchNextSyncPlan(), planWatchNextSync(listOf(dismissed), listOf(spec("a", engagedAt = 1_000L, positionMs = 1L))))

        val watchedAgain = spec("a", engagedAt = 5_000L, positionMs = 300_000L)
        assertEquals(
            WatchNextSyncPlan(inserts = listOf(watchedAgain), deletes = listOf(4L)),
            planWatchNextSync(listOf(dismissed), listOf(watchedAgain)),
        )
    }

    @Test
    fun duplicateRowsForOneItemCollapseToOne() {
        val a = spec("a")
        assertEquals(
            WatchNextSyncPlan(deletes = listOf(2L)),
            planWatchNextSync(listOf(published(1, a), published(2, a)), listOf(a)),
        )
    }

    @Test
    fun rowsWrittenWithoutAFingerprintAreRewritten() {
        val a = spec("a")
        assertEquals(
            WatchNextSyncPlan(updates = listOf(1L to a)),
            planWatchNextSync(listOf(published(1, a, fingerprint = null)), listOf(a)),
        )
    }
}
