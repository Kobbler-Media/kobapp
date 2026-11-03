package dev.kobbler.app.tv

import android.content.ContentValues
import android.database.DatabaseUtils
import android.media.tv.TvContract
import android.media.tv.TvContract.WatchNextPrograms
import dev.kobbler.app.data.InProgressItem
import dev.kobbler.app.watchnext.parseWatchNextLaunch
import dev.kobbler.app.watchnext.watchNextPrograms
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

private val Columns = arrayOf(
    WatchNextPrograms._ID,
    WatchNextPrograms.COLUMN_TYPE,
    WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
    WatchNextPrograms.COLUMN_TITLE,
    WatchNextPrograms.COLUMN_EPISODE_TITLE,
    WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
    WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
    WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
    WatchNextPrograms.COLUMN_DURATION_MILLIS,
    WatchNextPrograms.COLUMN_POSTER_ART_URI,
    WatchNextPrograms.COLUMN_POSTER_ART_ASPECT_RATIO,
    WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID,
    WatchNextPrograms.COLUMN_INTENT_URI,
    WatchNextPrograms.COLUMN_BROWSABLE,
)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WatchNextRowTest {
    private lateinit var provider: FakeTvProvider
    private lateinit var row: WatchNextRow
    private val host = "dev.kobbler.app.dbg"

    @Before
    fun setUp() {
        provider = Robolectric.setupContentProvider(FakeTvProvider::class.java, TvContract.AUTHORITY)
        row = WatchNextRow(RuntimeEnvironment.getApplication().contentResolver, host)
    }

    private fun episode(id: String, position: Int?, updatedAt: String = "2026-11-01T10:00:00Z") = InProgressItem(
        mediaType = "episode", mediaId = id, title = "Show $id", subtitle = "S02E05 - Pilot", imageUrl = "/poster/$id",
        link = "/tv/episodes/$id", positionSeconds = position, durationSeconds = 1200.0, updatedAt = updatedAt,
        backdropUrl = "/backdrop/$id",
    )

    private fun sync(profile: String, vararg items: InProgressItem) =
        row.sync(watchNextPrograms(items.toList(), profile, "https://kob.example"))

    private fun programs(): List<ContentValues> =
        provider.query(WatchNextPrograms.CONTENT_URI, Columns, null, null, null).use { c ->
            buildList { while (c.moveToNext()) add(ContentValues().also { DatabaseUtils.cursorRowToContentValues(c, it) }) }
        }

    private val ContentValues.position get() = getAsInteger(WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS)
    private val ContentValues.browsable get() = getAsInteger(WatchNextPrograms.COLUMN_BROWSABLE) == 1
    private val ContentValues.internalId get() = getAsString(WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID)

    @Test
    fun publishesAResumableEpisodeThatDeepLinksBackIntoTheProfile() {
        sync("p1", episode("e1", position = 300))

        val program = programs().single()
        assertEquals(WatchNextPrograms.TYPE_TV_EPISODE, program.getAsInteger(WatchNextPrograms.COLUMN_TYPE))
        assertEquals(WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE, program.getAsInteger(WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE))
        assertEquals("Show e1", program.getAsString(WatchNextPrograms.COLUMN_TITLE))
        assertEquals("Pilot", program.getAsString(WatchNextPrograms.COLUMN_EPISODE_TITLE))
        assertEquals("2", program.getAsString(WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER))
        assertEquals("5", program.getAsString(WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER))
        assertEquals(300_000, program.position)
        assertEquals(1_200_000, program.getAsInteger(WatchNextPrograms.COLUMN_DURATION_MILLIS))
        assertEquals("https://kob.example/backdrop/e1", program.getAsString(WatchNextPrograms.COLUMN_POSTER_ART_URI))
        assertEquals(WatchNextPrograms.ASPECT_RATIO_16_9, program.getAsInteger(WatchNextPrograms.COLUMN_POSTER_ART_ASPECT_RATIO))
        assertEquals("p1/episode/e1", program.internalId)
        val intentUri = program.getAsString(WatchNextPrograms.COLUMN_INTENT_URI)
        val launch = parseWatchNextLaunch(intentUri)!!
        assertEquals("p1", launch.profileId)
        assertEquals("/tv/episodes/e1", launch.link)
        assertEquals(host, android.net.Uri.parse(intentUri).host)
    }

    @Test
    fun progressUpdatesInPlaceAndFinishedItemsLeave() {
        sync("p1", episode("e1", position = 300), episode("e2", position = 60))
        val e1Row = provider.rows.entries.first { it.value.getAsString(WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID) == "p1/episode/e1" }.key

        sync("p1", episode("e1", position = 900, updatedAt = "2026-11-02T10:00:00Z"))

        assertEquals(listOf(e1Row), provider.rows.keys.toList())
        assertEquals(900_000, programs().single().position)
    }

    @Test
    fun aSecondSyncWithNothingNewWritesNothing() {
        sync("p1", episode("e1", position = 300))
        provider.rows.values.single().put(WatchNextPrograms.COLUMN_TITLE, "untouched")

        sync("p1", episode("e1", position = 300))

        assertEquals("untouched", provider.rows.values.single().getAsString(WatchNextPrograms.COLUMN_TITLE))
    }

    @Test
    fun switchingProfileReplacesTheRowAndSigningOutEmptiesIt() {
        sync("p1", episode("e1", position = 300))
        sync("p2", episode("e9", position = 10))
        assertEquals(listOf("p2/episode/e9"), programs().map { it.internalId })

        row.sync(emptyList())
        assertEquals(emptyList<Long>(), provider.rows.keys.toList())
    }

    @Test
    fun aProgramTheViewerRemovedIsNotPutBackUntilTheyWatchAgain() {
        sync("p1", episode("e1", position = 300))
        provider.rows.values.single().put(WatchNextPrograms.COLUMN_BROWSABLE, 0)

        sync("p1", episode("e1", position = 300))
        assertEquals(false, programs().single().browsable)

        sync("p1", episode("e1", position = 600, updatedAt = "2026-11-03T10:00:00Z"))
        val program = programs().single()
        assertEquals(true, program.browsable)
        assertEquals(600_000, program.position)
    }
}
