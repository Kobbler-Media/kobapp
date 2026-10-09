package dev.kobbler.app.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup
import dev.kobbler.app.data.CalendarItemDto
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.localToday
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.BrowseHeader
import dev.kobbler.app.ui.components.EmptyState
import dev.kobbler.app.ui.components.KobChip
import dev.kobbler.app.ui.components.KobChipStyle
import dev.kobbler.app.ui.components.SegmentedFilter
import dev.kobbler.app.ui.dashboard.calendar.AgendaList
import dev.kobbler.app.ui.dashboard.calendar.CalendarView
import dev.kobbler.app.ui.dashboard.calendar.DaySchedulePanel
import dev.kobbler.app.ui.dashboard.calendar.MEDIA_KINDS
import dev.kobbler.app.ui.dashboard.calendar.MonthGrid
import dev.kobbler.app.ui.dashboard.calendar.defaultMediaFilters
import dev.kobbler.app.ui.dashboard.calendar.mergeRange
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.plus

@Composable
fun CalendarScreen(
    api: KobblerApi,
    modifier: Modifier = Modifier,
    formFactor: FormFactor = FormFactor.Desktop,
    onItemClick: (CalendarItemDto) -> Unit = {},
) {
    val colors = KobappTheme.colors
    val dimens = KobappTheme.dimens
    val accent = colors.accCal
    val compact = formFactor == FormFactor.Phone
    val gutter = when (formFactor) {
        FormFactor.TV -> dimens.pageGutterTv
        FormFactor.Phone -> dimens.pageGutterPhone
        else -> dimens.pageGutter
    }

    val today = remember { localToday() }

    var rangeStart by remember { mutableStateOf(today.minus(45, DateTimeUnit.DAY)) }
    var rangeEnd by remember { mutableStateOf(today.plus(60, DateTimeUnit.DAY)) }
    var items by remember { mutableStateOf<List<CalendarItemDto>>(emptyList()) }
    var initialLoaded by remember { mutableStateOf(false) }
    var activeFilters by remember(formFactor) {
        mutableStateOf(defaultMediaFilters(formFactor))
    }
    var view by remember(formFactor) {
        mutableStateOf(
            if (formFactor == FormFactor.Desktop) CalendarView.Month else CalendarView.Agenda,
        )
    }
    var selectedDate by remember { mutableStateOf(today) }

    LaunchedEffect(Unit) {
        try {
            items = api.getCalendar(rangeStart.toString(), rangeEnd.toString())
        } finally {
            initialLoaded = true
        }
    }

    val filteredItems by remember(items, activeFilters) {
        derivedStateOf { items.filter { it.mediaType in activeFilters } }
    }

    val itemsByDate by remember(filteredItems) {
        derivedStateOf { filteredItems.groupBy { it.date } }
    }

    val monthCount by remember(filteredItems, selectedDate) {
        derivedStateOf {
            val ym = "${selectedDate.year}-${selectedDate.month.number.toString().padStart(2, '0')}"
            filteredItems.count { it.date.startsWith(ym) }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        Spacer(Modifier.height(dimens.s4))
        Box(Modifier.padding(horizontal = gutter)) {
            BrowseHeader(
                title = "Calendar",
                accent = accent,
                countLine = if (initialLoaded) {
                    "${monthName(selectedDate.month.number)} ${selectedDate.year} · $monthCount release${if (monthCount == 1) "" else "s"}"
                } else null,
                compact = compact,
                trailing = if (!compact) {
                    {
                        SegmentedFilter(
                            options = listOf("Month", "List"),
                            selectedIndex = if (view == CalendarView.Month) 0 else 1,
                            accent = accent,
                            onSelected = { idx ->
                                view = if (idx == 0) CalendarView.Month else CalendarView.Agenda
                            },
                        )
                    }
                } else null,
                secondaryRow = {
                    FilterChipRow(
                        activeFilters = activeFilters,
                        onToggle = { key ->
                            activeFilters = if (key in activeFilters) activeFilters - key
                            else activeFilters + key
                        },
                    )
                },
            )
        }

        Spacer(Modifier.height(if (compact) dimens.s3 else dimens.s4))

        if (!initialLoaded) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    color = accent,
                    modifier = Modifier.size(32.dp),
                )
            }
            return@Column
        }

        if (filteredItems.isEmpty() && view == CalendarView.Agenda) {
            val filtered = activeFilters.size < MEDIA_KINDS.size
            EmptyState(
                icon = Icons.Outlined.EventBusy,
                title = if (filtered) "Nothing matches these filters" else "Nothing upcoming",
                subtitle = if (filtered) {
                    "Turn a category back on to see its releases"
                } else {
                    "Releases will appear here as they're scheduled"
                },
            )
            return@Column
        }

        when {
            view == CalendarView.Agenda || compact -> {
                AgendaList(
                    items = filteredItems,
                    today = today,
                    apiUrl = api.apiUrl,
                    formFactor = formFactor,
                    onItemClick = onItemClick,
                    onLoadEarlier = {
                        val newStart = rangeStart.minus(30, DateTimeUnit.DAY)
                        try {
                            val earlier = api.getCalendar(
                                newStart.toString(),
                                rangeStart.minus(1, DateTimeUnit.DAY).toString(),
                            )
                            if (earlier.isNotEmpty()) items = earlier + items
                            rangeStart = newStart
                        } catch (_: Exception) {}
                    },
                    onLoadLater = {
                        val newEnd = rangeEnd.plus(30, DateTimeUnit.DAY)
                        try {
                            val later = api.getCalendar(
                                rangeEnd.plus(1, DateTimeUnit.DAY).toString(),
                                newEnd.toString(),
                            )
                            if (later.isNotEmpty()) items = items + later
                            rangeEnd = newEnd
                        } catch (_: Exception) {}
                    },
                    // Pulled in by the focus bleed the list adds back as content
                    // padding, so rows land on the gutter with room for their ring.
                    modifier = Modifier.padding(horizontal = gutter - dimens.focusBleed),
                )
            }
            else -> {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = gutter),
                    horizontalArrangement = Arrangement.spacedBy(dimens.s5),
                ) {
                    MonthGrid(
                        today = today,
                        itemsByDate = itemsByDate,
                        selectedDate = selectedDate,
                        onSelectDate = { selectedDate = it },
                        onLoadRange = { from, to ->
                            try {
                                val newItems = api.getCalendar(from.toString(), to.toString())
                                items = mergeRange(items, newItems, from, to)
                                if (from < rangeStart) rangeStart = from
                                if (to > rangeEnd) rangeEnd = to
                            } catch (_: Exception) {}
                        },
                        modifier = Modifier.weight(1f).fillMaxSize(),
                    )
                    DaySchedulePanel(
                        selectedDate = selectedDate,
                        today = today,
                        selectedItems = itemsByDate[selectedDate.toString()] ?: emptyList(),
                        todayItems = itemsByDate[today.toString()] ?: emptyList(),
                        apiUrl = api.apiUrl,
                        onItemClick = onItemClick,
                        modifier = Modifier.width(360.dp).fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterChipRow(
    activeFilters: Set<String>,
    onToggle: (String) -> Unit,
) {
    val colors = KobappTheme.colors
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.focusKeyGroup("filters"),
    ) {
        MEDIA_KINDS.forEach { kind ->
            val selected = kind.key in activeFilters
            val accent = kind.color(colors)
            KobChip(
                text = kind.label,
                style = if (selected) KobChipStyle.AccentTint else KobChipStyle.Ghost,
                accent = accent,
                onClick = { onToggle(kind.key) },
                modifier = Modifier.focusKey(kind.key),
            )
        }
    }
}

private fun monthName(month: Int): String = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)[month - 1]
