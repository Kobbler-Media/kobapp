package dev.kobbler.app.ui.dashboard.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup
import dev.kobbler.app.data.CalendarItemDto
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.tvAutoGlow
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.plus

@Composable
internal fun MonthGrid(
    today: LocalDate,
    itemsByDate: Map<String, List<CalendarItemDto>>,
    selectedDate: LocalDate,
    onSelectDate: (LocalDate) -> Unit,
    onLoadRange: suspend (LocalDate, LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    var viewYear by remember { mutableStateOf(today.year) }
    var viewMonth by remember { mutableStateOf(today.month.number) }

    LaunchedEffect(viewYear, viewMonth) {
        val firstOfMonth = LocalDate(viewYear, viewMonth, 1)
        val from = firstOfMonth.minus(20, DateTimeUnit.DAY)
        val to = firstOfMonth.plus(45, DateTimeUnit.DAY)
        onLoadRange(from, to)
    }

    val weeks = remember(viewYear, viewMonth, today) { buildMonthWeeks(viewYear, viewMonth, today) }
    val monthLabel = "${monthName(viewMonth)} $viewYear"

    Column(modifier = modifier.fillMaxSize()) {
        MonthNavBar(
            label = monthLabel,
            onPrev = {
                if (viewMonth == 1) { viewMonth = 12; viewYear -= 1 } else viewMonth -= 1
            },
            onNext = {
                if (viewMonth == 12) { viewMonth = 1; viewYear += 1 } else viewMonth += 1
            },
            onToday = {
                viewYear = today.year
                viewMonth = today.month.number
                onSelectDate(today)
            },
        )

        Spacer(Modifier.height(10.dp))
        WeekdayHeader()
        Spacer(Modifier.height(6.dp))

        Column(
            modifier = Modifier.fillMaxSize().focusKeyGroup("days"),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            weeks.forEach { week ->
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    week.forEach { cell ->
                        DayCell(
                            cell = cell,
                            dayItems = itemsByDate[cell.date.toString()] ?: emptyList(),
                            isSelected = cell.date == selectedDate,
                            onClick = { onSelectDate(cell.date) },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .focusKey(
                                    key = "day.${cell.date}",
                                    preferredEntry = cell.date == selectedDate,
                                ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthNavBar(
    label: String,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
) {
    val colors = KobappTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().focusKeyGroup("nav"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavIconButton(Icons.Outlined.ChevronLeft, onClick = onPrev, label = "Previous month", key = "prev")
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = KobappTheme.type.h2.copy(fontWeight = FontWeight.SemiBold),
            color = colors.textHi,
            modifier = Modifier
                .focusKey("month")
                .tvAutoGlow(cornerRadiusDp = 6f, scale = 1.02f)
                .clip(RoundedCornerShape(6.dp))
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(onClick = onToday)
                .padding(horizontal = 4.dp),
        )
        Spacer(Modifier.width(8.dp))
        NavIconButton(Icons.Outlined.ChevronRight, onClick = onNext, label = "Next month", key = "next")
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .focusKey("today")
                .tvAutoGlow(cornerRadiusDp = 8f, scale = 1.03f)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.elev1)
                .border(1.dp, colors.lineSoft, RoundedCornerShape(8.dp))
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(onClick = onToday)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                "Today",
                style = KobappTheme.type.caption.copy(fontWeight = FontWeight.SemiBold),
                color = colors.accCal,
            )
        }
    }
}

@Composable
private fun NavIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    label: String,
    key: String,
) {
    val colors = KobappTheme.colors
    Box(
        modifier = Modifier
            .size(32.dp)
            .focusKey(key)
            .tvAutoGlow(cornerRadiusDp = 8f, scale = 1.08f)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.elev1)
            .border(1.dp, colors.lineSoft, RoundedCornerShape(8.dp))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = colors.textMd, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun WeekdayHeader() {
    val colors = KobappTheme.colors
    Row(modifier = Modifier.fillMaxWidth()) {
        listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEach { wd ->
            Text(
                text = wd.uppercase(),
                style = KobappTheme.type.labelSm,
                color = colors.textLo,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).padding(vertical = 4.dp),
            )
        }
    }
}
