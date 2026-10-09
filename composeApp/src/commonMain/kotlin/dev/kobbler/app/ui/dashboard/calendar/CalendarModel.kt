package dev.kobbler.app.ui.dashboard.calendar

import androidx.compose.ui.graphics.Color
import dev.kobbler.app.data.CalendarItemDto
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappColors
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.datetime.until

internal enum class CalendarView { Agenda, Month }

internal data class MediaKind(val key: String, val label: String, val short: String) {
    fun color(c: KobappColors): Color = when (key) {
        "tv" -> c.accTv
        "movie" -> c.accMovies
        "book" -> c.accBooks
        "sport" -> c.accSports
        else -> c.textLo
    }
}

internal val MEDIA_KINDS = listOf(
    MediaKind("tv", "TV", "TV"),
    MediaKind("movie", "Movies", "Movie"),
    MediaKind("sport", "Sports", "Sport"),
    MediaKind("book", "Books", "Book"),
)

internal fun defaultMediaFilters(formFactor: FormFactor): Set<String> {
    val all = MEDIA_KINDS.mapTo(LinkedHashSet()) { it.key }
    return if (formFactor == FormFactor.TV) all - "book" else all
}

internal fun mediaKindFor(type: String): MediaKind =
    MEDIA_KINDS.firstOrNull { it.key == type } ?: MediaKind(type, type, type)

internal data class GridCell(val date: LocalDate, val inMonth: Boolean, val isToday: Boolean)

internal fun buildMonthWeeks(year: Int, month: Int, today: LocalDate): List<List<GridCell>> {
    val first = LocalDate(year, month, 1)
    val leading = first.dayOfWeek.isoDayNumber - 1
    val gridStart = first.minus(leading, DateTimeUnit.DAY)
    val lastInMonth = LocalDate(year, month, daysInMonth(year, month))

    val weeks = mutableListOf<List<GridCell>>()
    var cursor = gridStart
    repeat(6) {
        val week = (0 until 7).map { offset ->
            val d = cursor.plus(offset, DateTimeUnit.DAY)
            GridCell(
                date = d,
                inMonth = d.month.number == month && d.year == year,
                isToday = d == today,
            )
        }
        weeks.add(week)
        cursor = cursor.plus(7, DateTimeUnit.DAY)
        if (week.last().date >= lastInMonth && week.last().date.month.number != month) {
            return@repeat
        }
    }
    while (weeks.size > 4 && weeks.last().all { !it.inMonth }) {
        weeks.removeAt(weeks.size - 1)
    }
    return weeks
}

internal fun daysInMonth(year: Int, month: Int): Int {
    val first = LocalDate(year, month, 1)
    val nextFirst = if (month == 12) LocalDate(year + 1, 1, 1) else LocalDate(year, month + 1, 1)
    return first.until(nextFirst, DateTimeUnit.DAY).toInt()
}

internal fun monthName(month: Int): String = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)[month - 1]

internal fun monthNameShort(month: Int): String = monthName(month).take(3)

internal fun formatLongDate(date: LocalDate): String {
    val dow = date.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
    return "$dow, ${monthName(date.month.number)} ${date.day}"
}

internal fun formatShortDate(date: LocalDate): String {
    val dow = date.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)
    return "$dow · ${monthNameShort(date.month.number)} ${date.day}"
}

internal fun resolveImageUrl(raw: String?, apiUrl: String): String? = raw?.let { url ->
    if (url.startsWith("http")) url else "$apiUrl$url"
}

/**
 * Index of the agenda group the list should open on: the first day on or after [today],
 * or the last loaded day when the whole range is in the past.
 *
 * [groups] is (date, item count) in ascending date order; the indices count the rows
 * AgendaList actually emits per group — a header, one row per item, and a trailing
 * spacer.
 */
internal fun agendaAnchor(groups: List<Pair<String, Int>>, today: String): AgendaAnchor? {
    if (groups.isEmpty()) return null
    val target = groups.indexOfFirst { it.first >= today }.takeIf { it >= 0 } ?: groups.lastIndex
    var headerIndex = 0
    for (i in 0 until target) headerIndex += 2 + groups[i].second
    return AgendaAnchor(
        date = groups[target].first,
        headerIndex = headerIndex,
        isToday = groups[target].first == today,
    )
}

internal data class AgendaAnchor(val date: String, val headerIndex: Int, val isToday: Boolean)

internal fun mergeRange(
    existing: List<CalendarItemDto>,
    incoming: List<CalendarItemDto>,
    from: LocalDate,
    to: LocalDate,
): List<CalendarItemDto> {
    val fromStr = from.toString()
    val toStr = to.toString()
    val kept = existing.filter { it.date < fromStr || it.date > toStr }
    return (kept + incoming).sortedBy { it.date }
}
