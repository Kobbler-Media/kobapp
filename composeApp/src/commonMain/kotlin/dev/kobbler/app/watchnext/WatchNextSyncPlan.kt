package dev.kobbler.app.watchnext

data class PublishedProgram(
    val rowId: Long,
    val internalId: String,
    val fingerprint: String?,
    val browsable: Boolean,
    val lastEngagementMs: Long,
)

data class WatchNextSyncPlan(
    val inserts: List<WatchNextProgramSpec> = emptyList(),
    val updates: List<Pair<Long, WatchNextProgramSpec>> = emptyList(),
    val deletes: List<Long> = emptyList(),
)

fun planWatchNextSync(published: List<PublishedProgram>, desired: List<WatchNextProgramSpec>): WatchNextSyncPlan {
    val byId = published.groupBy { it.internalId }
    val wanted = desired.associateBy { it.internalId }
    val inserts = mutableListOf<WatchNextProgramSpec>()
    val updates = mutableListOf<Pair<Long, WatchNextProgramSpec>>()
    val deletes = mutableListOf<Long>()
    byId.forEach { (id, rows) ->
        rows.drop(1).forEach { deletes += it.rowId }
        if (id !in wanted) deletes += rows.first().rowId
    }
    desired.forEach { spec ->
        val row = byId[spec.internalId]?.first()
        when {
            row == null -> inserts += spec
            // The viewer removed it from the row; the system keeps it unbrowsable rather than
            // deleting it. Only coming back to it earns it a place again.
            !row.browsable -> if (spec.lastEngagementMs > row.lastEngagementMs) {
                deletes += row.rowId
                inserts += spec
            }
            row.fingerprint != spec.fingerprint -> updates += row.rowId to spec
        }
    }
    return WatchNextSyncPlan(inserts, updates, deletes.sorted())
}
