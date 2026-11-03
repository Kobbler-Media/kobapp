package dev.kobbler.app.tv

import android.content.ContentResolver
import android.media.tv.TvContract
import android.media.tv.TvContract.WatchNextPrograms
import androidx.annotation.RequiresApi
import dev.kobbler.app.watchnext.PublishedProgram
import dev.kobbler.app.watchnext.WatchNextProgramSpec
import dev.kobbler.app.watchnext.planWatchNextSync

@RequiresApi(26)
class WatchNextRow(private val resolver: ContentResolver, private val host: String) {
    fun sync(desired: List<WatchNextProgramSpec>) {
        val plan = planWatchNextSync(published(), desired)
        plan.deletes.forEach { resolver.delete(TvContract.buildWatchNextProgramUri(it), null, null) }
        plan.updates.forEach { (rowId, spec) ->
            resolver.update(TvContract.buildWatchNextProgramUri(rowId), spec.toContentValues(host), null, null)
        }
        plan.inserts.forEach { resolver.insert(WatchNextPrograms.CONTENT_URI, it.toContentValues(host)) }
    }

    // The provider only ever returns this package's own rows.
    private fun published(): List<PublishedProgram> =
        resolver.query(WatchNextPrograms.CONTENT_URI, Projection, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        PublishedProgram(
                            rowId = cursor.getLong(0),
                            internalId = cursor.getString(1).orEmpty(),
                            fingerprint = if (cursor.isNull(2)) null else cursor.getBlob(2).decodeToString(),
                            browsable = cursor.getInt(3) == 1,
                            lastEngagementMs = cursor.getLong(4),
                        ),
                    )
                }
            }
        }.orEmpty()

    private companion object {
        val Projection = arrayOf(
            WatchNextPrograms._ID,
            WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID,
            WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_DATA,
            WatchNextPrograms.COLUMN_BROWSABLE,
            WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
        )
    }
}
