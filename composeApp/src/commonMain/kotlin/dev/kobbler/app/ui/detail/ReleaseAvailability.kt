package dev.kobbler.app.ui.detail

import androidx.compose.runtime.Immutable
import dev.kobbler.app.data.LibraryStatus
import dev.kobbler.app.data.ReleaseDates
import dev.kobbler.app.ui.detail.tvshow.monthDay
import kotlinx.datetime.LocalDate

enum class StreamingAvailability { Hidden, Checking, Instant, Available, None }

@Immutable
internal data class AvailabilityRow(val label: String, val value: String, val emphasised: Boolean)

@Immutable
internal data class ReleaseAvailability(val rows: List<AvailabilityRow>, val regionNote: String? = null)

internal fun streamingAvailability(canStream: Boolean, searching: Boolean, found: List<Boolean>?): StreamingAvailability = when {
    !canStream -> StreamingAvailability.Hidden
    found == null -> if (searching) StreamingAvailability.Checking else StreamingAvailability.Hidden
    found.any { it } -> StreamingAvailability.Instant
    found.isNotEmpty() -> StreamingAvailability.Available
    else -> StreamingAvailability.None
}

internal fun releaseAvailability(
    releaseDate: LocalDate?,
    year: Int?,
    dates: ReleaseDates?,
    userRegion: String?,
    today: LocalDate,
    streaming: StreamingAvailability,
    library: LibraryStatus,
): ReleaseAvailability {
    val typed = dates?.let { typedRows(it, today) }.orEmpty()
    val release = typed.ifEmpty {
        listOf(datedRow("Release", releaseDate, today) ?: AvailabilityRow("Release", year?.toString() ?: "—", false))
    }
    return ReleaseAvailability(
        rows = release + listOfNotNull(
            when (streaming) {
                StreamingAvailability.Hidden -> null
                StreamingAvailability.Checking -> AvailabilityRow("Streaming", "Checking…", false)
                StreamingAvailability.Instant -> AvailabilityRow("Streaming", "Ready to stream", true)
                StreamingAvailability.Available -> AvailabilityRow("Streaming", "Available", true)
                StreamingAvailability.None -> AvailabilityRow("Streaming", "Not yet", false)
            },
            when (library) {
                LibraryStatus.Wanted -> AvailabilityRow("Library", "Wanted", true)
                LibraryStatus.InLibrary -> AvailabilityRow("Library", "In library", true)
                LibraryStatus.NotInLibrary -> AvailabilityRow("Library", "Not in library", false)
            },
        ),
        regionNote = dates?.region?.uppercase()
            ?.takeIf { typed.isNotEmpty() && !it.equals(userRegion, ignoreCase = true) }
            ?.let { "Dates for $it" },
    )
}

private fun typedRows(dates: ReleaseDates, today: LocalDate): List<AvailabilityRow> = listOfNotNull(
    datedRow("Cinema", dates.cinema, today),
    datedRow("Digital", dates.digital, today),
    datedRow("Physical", dates.physical, today),
)

private fun datedRow(label: String, date: LocalDate?, today: LocalDate): AvailabilityRow? = date?.let {
    if (it > today) AvailabilityRow(label, "Coming ${it.label()}", true) else AvailabilityRow(label, it.label(), false)
}

private fun LocalDate.label() = "${monthDay(this)}, $year"
