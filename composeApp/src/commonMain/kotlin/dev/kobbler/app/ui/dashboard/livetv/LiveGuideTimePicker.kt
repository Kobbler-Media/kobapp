package dev.kobbler.app.ui.dashboard.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.KobChip
import dev.kobbler.app.ui.components.KobChipStyle
import dev.kobbler.app.ui.navigation.backInput
import dev.kobbler.kobfocus.focusKey
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

internal data class GuideDay(val date: LocalDate, val label: String, val hours: List<Instant>)

/** Whole hours the guide can answer for, grouped by local day: from the next hour up to its horizon. */
internal fun guideDays(now: Instant, tz: TimeZone = TimeZone.currentSystemDefault()): List<GuideDay> {
    val horizon = now + GuideFutureHours.hours
    val today = now.toLocalDateTime(tz).date
    val last = horizon.toLocalDateTime(tz).date
    val days = generateSequence(today) { d -> d.plus(DatePeriod(days = 1)).takeIf { it <= last } }
    return days.mapIndexedNotNull { i, date ->
        val hours = (0..23).map { date.atTime(LocalTime(it, 0)).toInstant(tz) }.filter { it > now && it <= horizon }
        if (hours.isEmpty()) return@mapIndexedNotNull null
        val label = when (i) {
            0 -> "Today"
            1 -> "Tomorrow"
            else -> "${DayNames[date.dayOfWeek.ordinal]} ${date.day}"
        }
        GuideDay(date, label, hours)
    }.toList()
}

internal fun guideTimeLabel(at: Instant?): String = when {
    at == null -> "Now"
    at.isLocalToday() -> at.localHhMm()
    else -> "${at.localDayLabel()} ${at.localHhMm()}"
}

@Composable
internal fun GuideTimeChip(at: Instant?, accent: Color, onClick: () -> Unit) {
    KobChip(
        text = guideTimeLabel(at),
        style = if (at != null) KobChipStyle.AccentTint else KobChipStyle.Default,
        accent = accent,
        leading = { Icon(Icons.Outlined.CalendarMonth, contentDescription = null, modifier = Modifier.size(15.dp)) },
        onClick = onClick,
        modifier = Modifier.focusKey("guidetime", entryEligible = false).semantics { contentDescription = "Pick a time" },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GuideTimePicker(at: Instant?, accent: Color, onPick: (Instant?) -> Unit, onDismiss: () -> Unit) {
    val colors = KobappTheme.colors
    val now = remember { nowInstant() }
    val days = remember(now) { guideDays(now) }
    val tz = TimeZone.currentSystemDefault()
    var day by remember { mutableStateOf(days.firstOrNull { d -> at != null && at.toLocalDateTime(tz).date == d.date } ?: days.firstOrNull()) }
    val entry = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(10) {
            if (runCatching { entry.requestFocus() }.isSuccess) return@LaunchedEffect
            withFrameNanos { }
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        val focusManager = LocalFocusManager.current
        // A dialog window is outside the scaffold's key handling, which is what turns arrows
        // into focus moves on desktop and routes BACK; Android TV does both natively.
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.elev1)
                .backInput { onDismiss(); true }
                .onPreviewKeyEvent { event ->
                    val direction = when (event.key) {
                        Key.DirectionLeft -> FocusDirection.Left
                        Key.DirectionRight -> FocusDirection.Right
                        Key.DirectionUp -> FocusDirection.Up
                        Key.DirectionDown -> FocusDirection.Down
                        else -> null
                    }
                    direction != null && event.type == KeyEventType.KeyDown && focusManager.moveFocus(direction)
                }
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Text("What's on at…", style = KobappTheme.type.h3, color = colors.textHi)
            KobChip(
                text = "Now",
                style = if (at == null) KobChipStyle.AccentTint else KobChipStyle.Default,
                accent = accent,
                onClick = { onPick(null) },
                modifier = Modifier.focusKey("time.now").focusRequester(entry),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                days.forEach { d ->
                    KobChip(
                        text = d.label,
                        style = if (d == day) KobChipStyle.AccentTint else KobChipStyle.Default,
                        accent = accent,
                        onClick = { day = d },
                        modifier = Modifier.focusKey("day.${d.date}"),
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                day?.hours?.forEach { hour ->
                    KobChip(
                        text = hour.localHhMm(),
                        style = if (hour == at) KobChipStyle.AccentTint else KobChipStyle.Default,
                        accent = accent,
                        onClick = { onPick(hour) },
                        modifier = Modifier.focusKey("hour.${hour.toEpochMilliseconds()}"),
                    )
                }
            }
        }
    }
}
