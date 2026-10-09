package dev.kobbler.app.ui.dashboard.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.CalendarItemDto
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.tvAutoGlow

// A titled chip needs room for a leading dot, its own padding and enough glyphs to
// still read as a name. Below this the cell falls back to per-kind dots — a TV month
// grid gives each day roughly 50dp of text width, which truncates every title to one
// or two characters.
private val TITLE_MIN_WIDTH = 116.dp

@Composable
internal fun DayCell(
    cell: GridCell,
    dayItems: List<CalendarItemDto>,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KobappTheme.colors
    val accent = colors.accCal
    val borderColor = when {
        isSelected -> accent
        cell.isToday -> accent.copy(alpha = 0.6f)
        else -> colors.lineSoft
    }
    val borderWidth = if (isSelected || cell.isToday) 1.5.dp else 1.dp
    val cellBg = when {
        isSelected -> accent.copy(alpha = 0.10f)
        cell.inMonth -> colors.elev1
        else -> colors.bg
    }
    BoxWithConstraints(
        modifier = modifier
            .heightIn(min = 92.dp)
            .tvAutoGlow(cornerRadiusDp = 8f, scale = 1.01f)
            .clip(RoundedCornerShape(8.dp))
            .background(cellBg)
            .border(borderWidth, borderColor, RoundedCornerShape(8.dp))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        val titlesFit = maxWidth >= TITLE_MIN_WIDTH
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(if (titlesFit) 4.dp else 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = cell.date.day.toString(),
                    style = KobappTheme.type.bodySm.copy(
                        fontWeight = if (cell.isToday) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = when {
                        cell.isToday -> accent
                        !cell.inMonth -> colors.textXlo
                        else -> colors.textMd
                    },
                )
                if (cell.isToday) {
                    Spacer(Modifier.size(4.dp))
                    Box(
                        Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(accent),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (dayItems.isNotEmpty()) {
                    Text(
                        text = dayItems.size.toString(),
                        style = KobappTheme.type.caption.copy(fontWeight = FontWeight.SemiBold),
                        color = if (cell.inMonth) colors.textMd else colors.textLo,
                    )
                }
            }

            if (titlesFit) {
                val maxVisible = 3
                dayItems.take(maxVisible).forEach { item -> DayChip(item) }
                if (dayItems.size > maxVisible) {
                    Text(
                        text = "+${dayItems.size - maxVisible}",
                        style = KobappTheme.type.labelSm,
                        color = colors.textLo,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            } else if (dayItems.isNotEmpty()) {
                KindDots(dayItems, dimmed = !cell.inMonth)
            }
        }
    }
}

@Composable
private fun KindDots(dayItems: List<CalendarItemDto>, dimmed: Boolean) {
    val colors = KobappTheme.colors
    val present = MEDIA_KINDS.filter { kind -> dayItems.any { it.mediaType == kind.key } }
    val extra = dayItems.filter { item -> MEDIA_KINDS.none { it.key == item.mediaType } }
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        present.forEach { kind ->
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(kind.color(colors).copy(alpha = if (dimmed) 0.45f else 1f)),
            )
        }
        if (extra.isNotEmpty()) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(colors.textLo.copy(alpha = if (dimmed) 0.45f else 1f)),
            )
        }
    }
}

@Composable
private fun DayChip(item: CalendarItemDto) {
    val colors = KobappTheme.colors
    val kind = mediaKindFor(item.mediaType)
    val accent = kind.color(colors)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(accent.copy(alpha = 0.18f))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(accent),
        )
        Text(
            text = item.title,
            style = KobappTheme.type.caption,
            color = colors.textHi,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
