package dev.kobbler.app.ui.dashboard.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup
import dev.kobbler.app.data.CalendarItemDto
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.TvFocusScale
import dev.kobbler.app.ui.components.tvAutoGlow
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil

@Composable
internal fun DaySchedulePanel(
    selectedDate: LocalDate,
    today: LocalDate,
    selectedItems: List<CalendarItemDto>,
    todayItems: List<CalendarItemDto>,
    apiUrl: String,
    onItemClick: (CalendarItemDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    val showTodayPreview = selectedDate != today && todayItems.isNotEmpty()
    val bleed = KobappTheme.dimens.focusBleed
    LazyColumn(
        modifier = modifier.fillMaxSize().focusKeyGroup("schedule"),
        // A row's focus ring paints outside its bounds and the list clips at its own
        // edge, so the rows have to sit inside a bleed margin or the ring is sliced.
        contentPadding = PaddingValues(start = bleed, end = bleed, top = bleed, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item {
            ScheduleSectionHeader(
                badge = if (selectedDate == today) "TODAY" else dayBadge(selectedDate, today),
                date = formatLongDate(selectedDate),
                count = selectedItems.size,
            )
        }
        if (selectedItems.isEmpty()) {
            item {
                EmptyDayBlock()
            }
        } else {
            items(selectedItems, key = { "${it.id}-${it.mediaType}" }) { item ->
                ScheduleRow(item, apiUrl, key = "selected.${item.id}.${item.mediaType}", onClick = { onItemClick(item) })
                Spacer(Modifier.height(8.dp))
            }
        }

        if (showTodayPreview) {
            item {
                Spacer(Modifier.height(16.dp))
                ScheduleSectionHeader(
                    badge = "TODAY",
                    date = formatLongDate(today),
                    count = todayItems.size,
                )
            }
            items(todayItems, key = { "today-${it.id}-${it.mediaType}" }) { item ->
                ScheduleRow(item, apiUrl, key = "today.${item.id}.${item.mediaType}", onClick = { onItemClick(item) })
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

private fun dayBadge(date: LocalDate, today: LocalDate): String {
    val diff = today.daysUntil(date)
    return when {
        diff == -1 -> "YESTERDAY"
        diff == 1 -> "TOMORROW"
        diff in 2..6 -> "THIS WEEK"
        diff < -1 -> "PAST"
        else -> "UPCOMING"
    }
}

@Composable
private fun ScheduleSectionHeader(
    badge: String,
    date: String,
    count: Int,
) {
    val colors = KobappTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            badge,
            style = KobappTheme.type.label,
            color = colors.accCal,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            date,
            style = KobappTheme.type.bodySm,
            color = colors.textLo,
        )
        Spacer(Modifier.weight(1f))
        Text(
            if (count == 0) "—" else "$count",
            style = KobappTheme.type.bodySm,
            color = colors.textLo,
        )
    }
}

@Composable
private fun EmptyDayBlock() {
    val colors = KobappTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .tvAutoGlow(cornerRadiusDp = 10f, scale = 1.01f)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.elev1)
            .border(1.dp, colors.lineSoft, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "No releases",
            style = KobappTheme.type.caption,
            color = colors.textLo,
        )
    }
}

@Composable
internal fun ScheduleRow(
    item: CalendarItemDto,
    apiUrl: String,
    key: String,
    onClick: () -> Unit,
    preferredEntry: Boolean = false,
) {
    val colors = KobappTheme.colors
    val kind = mediaKindFor(item.mediaType)
    val accent = kind.color(colors)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusKey(key, preferredEntry = preferredEntry)
            .tvAutoGlow(cornerRadiusDp = 10f, scale = TvFocusScale.None)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.elev1)
            .border(1.dp, colors.lineSoft, RoundedCornerShape(10.dp))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(40.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accent),
        )

        val imageUrl = resolveImageUrl(item.imageUrl, apiUrl)
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 36.dp, height = 50.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(colors.elev2),
            )
        } else {
            Box(
                Modifier
                    .size(width = 36.dp, height = 50.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(accent.copy(alpha = 0.18f)),
            )
        }

        Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Text(
                text = item.title,
                style = KobappTheme.type.body.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textHi,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!item.subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = item.subtitle,
                    style = KobappTheme.type.bodySm,
                    color = colors.textLo,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Box(
            Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(accent.copy(alpha = 0.18f))
                .border(1.dp, accent.copy(alpha = 0.30f), RoundedCornerShape(999.dp))
                .padding(horizontal = 9.dp, vertical = 3.dp),
        ) {
            Text(
                kind.short,
                style = KobappTheme.type.label,
                color = accent,
            )
        }
    }
}
