package dev.kobbler.app.ui.detail.tvshow

import dev.kobbler.app.ui.dashboard.calendar.monthNameShort
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number

internal fun airDateLabel(date: LocalDate, today: LocalDate): String = when {
    date == today -> "Today"
    date < today -> "${monthDay(date)}, ${date.year}"
    date.year == today.year -> "Airs ${monthDay(date)}"
    else -> "Airs ${monthDay(date)}, ${date.year}"
}

internal fun upcomingLabel(date: LocalDate, today: LocalDate): String = when {
    date < today -> "Coming soon"
    date == today -> "Airs today"
    date.toEpochDays() - today.toEpochDays() == 1L -> "Airs tomorrow"
    date.year == today.year -> "Airs ${monthDay(date)}"
    else -> "Airs ${monthDay(date)}, ${date.year}"
}

internal fun monthDay(date: LocalDate): String = "${monthNameShort(date.month.number)} ${date.day}"
