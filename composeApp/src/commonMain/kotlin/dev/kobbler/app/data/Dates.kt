package dev.kobbler.app.data

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

fun localToday(): LocalDate = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date

fun parseIsoDate(value: String?): LocalDate? =
    value?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
