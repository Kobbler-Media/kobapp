package dev.kobbler.app.ui.detail

import dev.kobbler.app.data.LibraryStatus
import dev.kobbler.app.data.ReleaseDates
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReleaseAvailabilityTest {

    private val today = LocalDate(2026, 10, 21)

    private fun availability(
        releaseDate: LocalDate? = null,
        year: Int? = 1999,
        dates: ReleaseDates? = null,
        userRegion: String? = "AU",
        streaming: StreamingAvailability = StreamingAvailability.Hidden,
        library: LibraryStatus = LibraryStatus.NotInLibrary,
    ) = releaseAvailability(releaseDate, year, dates, userRegion, today, streaming, library)

    private fun rows(
        releaseDate: LocalDate? = null,
        year: Int? = 1999,
        dates: ReleaseDates? = null,
        streaming: StreamingAvailability = StreamingAvailability.Hidden,
        library: LibraryStatus = LibraryStatus.NotInLibrary,
    ) = availability(releaseDate, year, dates, streaming = streaming, library = library).rows.associate { it.label to it.value }

    private val past = LocalDate(2026, 2, 1)
    private val future = LocalDate(2026, 12, 4)

    @Test
    fun `release types read as dates, upcoming ones as coming, and unknown ones drop out`() {
        data class Case(val name: String, val dates: ReleaseDates, val cinema: String?, val digital: String?, val physical: String?)
        listOf(
            Case("all known", ReleaseDates(theatrical = past, digital = future, physical = future), "Feb 1, 2026", "Coming Dec 4, 2026", "Coming Dec 4, 2026"),
            Case("limited stands in for wide", ReleaseDates(theatricalLimited = past, premiere = LocalDate(2025, 9, 1)), "Feb 1, 2026", null, null),
            Case("premiere is the last resort", ReleaseDates(premiere = future, digital = past), "Coming Dec 4, 2026", "Feb 1, 2026", null),
            Case("wide wins over limited", ReleaseDates(theatrical = future, theatricalLimited = past), "Coming Dec 4, 2026", null, null),
            Case("no cinema date", ReleaseDates(physical = past), null, null, "Feb 1, 2026"),
            Case("today has happened", ReleaseDates(theatrical = today), "Oct 21, 2026", null, null),
        ).forEach { case ->
            val r = rows(releaseDate = LocalDate(1999, 3, 31), dates = case.dates)
            assertEquals(case.cinema, r["Cinema"], case.name)
            assertEquals(case.digital, r["Digital"], case.name)
            assertEquals(case.physical, r["Physical"], case.name)
            assertNull(r["Release"], case.name)
        }
    }

    @Test
    fun `only what is still to come is emphasised`() {
        val r = availability(dates = ReleaseDates(theatrical = past, digital = future)).rows.associate { it.label to it.emphasised }
        assertEquals(false, r["Cinema"])
        assertEquals(true, r["Digital"])
    }

    @Test
    fun `dates with nothing usable in them fall back to the single release date`() {
        listOf(ReleaseDates(), ReleaseDates(region = "US", tv = past)).forEach { dates ->
            val r = rows(releaseDate = LocalDate(1999, 3, 31), dates = dates)
            assertEquals("Mar 31, 1999", r["Release"], dates.toString())
            assertNull(r["Cinema"], dates.toString())
        }
    }

    @Test
    fun `the release types keep the streaming and library rows after them`() {
        val labels = availability(
            dates = ReleaseDates(theatrical = past, digital = past, physical = past),
            streaming = StreamingAvailability.Instant, library = LibraryStatus.Wanted,
        ).rows.map { it.label }
        assertEquals(listOf("Cinema", "Digital", "Physical", "Streaming", "Library"), labels)
    }

    @Test
    fun `a region other than the viewer's is noted`() {
        data class Case(val region: String?, val user: String?, val note: String?)
        listOf(
            Case("AU", "AU", null),
            Case("au", "AU", null),
            Case("US", "AU", "Dates for US"),
            Case(null, "AU", null),
            Case("US", null, "Dates for US"),
        ).forEach { case ->
            val note = availability(dates = ReleaseDates(region = case.region, theatrical = past), userRegion = case.user).regionNote
            assertEquals(case.note, note, case.toString())
        }
        assertNull(availability(dates = null, userRegion = "AU").regionNote)
        assertNull(availability(dates = ReleaseDates(region = "US", tv = past), userRegion = "AU").regionNote)
    }

    @Test
    fun `release reads as a date, an upcoming one as coming, and falls back to the year`() {
        assertEquals("Mar 31, 1999", rows(releaseDate = LocalDate(1999, 3, 31))["Release"])
        assertEquals("Coming Dec 4, 2026", rows(releaseDate = LocalDate(2026, 12, 4))["Release"])
        assertEquals("1999", rows()["Release"])
        assertEquals("—", rows(year = null)["Release"])
    }

    @Test
    fun `streaming says what the search found, and is absent where streaming is not set up`() {
        assertEquals(null, rows()["Streaming"])
        listOf(
            StreamingAvailability.Checking to "Checking…",
            StreamingAvailability.Instant to "Ready to stream",
            StreamingAvailability.Available to "Available",
            StreamingAvailability.None to "Not yet",
        ).forEach { (state, label) -> assertEquals(label, rows(streaming = state)["Streaming"], state.name) }
    }

    @Test
    fun `library names the title's status`() {
        assertEquals("Wanted", rows(library = LibraryStatus.Wanted)["Library"])
        assertEquals("In library", rows(library = LibraryStatus.InLibrary)["Library"])
        assertEquals("Not in library", rows()["Library"])
    }

    @Test
    fun `a search result maps to a streaming state`() {
        assertEquals(StreamingAvailability.Hidden, streamingAvailability(canStream = false, searching = true, found = null))
        assertEquals(StreamingAvailability.Checking, streamingAvailability(canStream = true, searching = true, found = null))
        assertEquals(StreamingAvailability.None, streamingAvailability(canStream = true, searching = false, found = emptyList()))
        assertEquals(StreamingAvailability.Instant, streamingAvailability(canStream = true, searching = false, found = listOf(true, false)))
        assertEquals(StreamingAvailability.Available, streamingAvailability(canStream = true, searching = false, found = listOf(false)))
    }
}
