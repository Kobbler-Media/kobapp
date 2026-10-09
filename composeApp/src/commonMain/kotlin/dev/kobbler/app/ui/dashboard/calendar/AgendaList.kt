package dev.kobbler.app.ui.dashboard.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
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
import dev.kobbler.kobfocus.LocalFocusScope
import dev.kobbler.kobfocus.focusKeyGroup
import dev.kobbler.app.data.CalendarItemDto
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil

@Composable
internal fun AgendaList(
    items: List<CalendarItemDto>,
    today: LocalDate,
    apiUrl: String,
    formFactor: FormFactor,
    onItemClick: (CalendarItemDto) -> Unit,
    onLoadEarlier: suspend () -> Unit,
    onLoadLater: suspend () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bleed = KobappTheme.dimens.focusBleed
    val listState = rememberLazyListState()
    var hasScrolled by remember { mutableStateOf(false) }
    var isLoadingMore by remember { mutableStateOf(false) }
    val focusScope = LocalFocusScope.current

    val grouped = remember(items) {
        items.groupBy { it.date }.entries.sortedBy { it.key }.associate { it.toPair() }
    }

    val anchor = remember(grouped, today) {
        agendaAnchor(grouped.map { it.key to it.value.size }, today.toString())
    }
    val anchorItem = anchor?.let { grouped[it.date]?.firstOrNull() }

    LaunchedEffect(anchor, hasScrolled) {
        if (hasScrolled || anchor == null) return@LaunchedEffect
        listState.scrollToItem(anchor.headerIndex)
        hasScrolled = true
        // The entry-focus race is unwinnable by preferredEntry alone: FocusScopeHost
        // gives up after a handful of frames, long before the calendar fetch returns,
        // so the header chips win by default. Claim it back once the row exists.
        if (formFactor == FormFactor.TV && anchorItem != null) {
            focusScope.restore("$AGENDA_GROUP.${rowKey(anchorItem, anchor.date)}")
        }
    }

    val shouldLoadEarlier by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex <= 5 && !isLoadingMore && hasScrolled
        }
    }
    val shouldLoadLater by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = listState.layoutInfo.totalItemsCount
            lastVisible >= total - 5 && !isLoadingMore && hasScrolled
        }
    }

    LaunchedEffect(shouldLoadEarlier) {
        if (!shouldLoadEarlier) return@LaunchedEffect
        isLoadingMore = true
        val before = listState.firstVisibleItemIndex
        val offset = listState.firstVisibleItemScrollOffset
        val totalBefore = listState.layoutInfo.totalItemsCount
        onLoadEarlier()
        val totalAfter = listState.layoutInfo.totalItemsCount
        val prepended = totalAfter - totalBefore
        if (prepended > 0) listState.scrollToItem(before + prepended, offset)
        isLoadingMore = false
    }

    LaunchedEffect(shouldLoadLater) {
        if (!shouldLoadLater) return@LaunchedEffect
        isLoadingMore = true
        onLoadLater()
        isLoadingMore = false
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().focusKeyGroup(AGENDA_GROUP),
        // A row's focus ring paints outside its bounds and the list clips at its own
        // edge; CalendarScreen pulls its gutter in by the same amount so the rows stay
        // on the page gutter while the ring gets room on both sides.
        contentPadding = PaddingValues(start = bleed, end = bleed, top = bleed, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        grouped.forEach { (date, dayItems) ->
            item(key = "header-$date") {
                AgendaDateHeader(date, today)
            }
            items(dayItems, key = { "${it.id}-${it.mediaType}-$date" }) { item ->
                ScheduleRow(
                    item = item,
                    apiUrl = apiUrl,
                    key = rowKey(item, date),
                    preferredEntry = item === anchorItem,
                    onClick = { onItemClick(item) },
                )
            }
            item(key = "spacer-$date") { Spacer(Modifier.height(4.dp)) }
        }
    }
}

private const val AGENDA_GROUP = "agenda"

private fun rowKey(item: CalendarItemDto, date: String) = "${item.id}.${item.mediaType}.$date"

@Composable
private fun AgendaDateHeader(dateStr: String, today: LocalDate) {
    val colors = KobappTheme.colors
    val date = runCatching { LocalDate.parse(dateStr) }.getOrNull()
    val (badge, sub) = if (date != null) {
        val diff = today.daysUntil(date)
        val badge = when (diff) {
            0 -> "TODAY"
            -1 -> "YESTERDAY"
            1 -> "TOMORROW"
            in 2..6 -> "THIS WEEK"
            else -> if (diff < 0) "PAST" else "UPCOMING"
        }
        badge to formatShortDate(date)
    } else "" to dateStr

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            badge,
            style = KobappTheme.type.label,
            color = colors.accCal,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            sub,
            style = KobappTheme.type.bodySm,
            color = colors.textLo,
        )
    }
}
