package dev.kobbler.kobplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class TvPicker { Chapters, Subtitles, Audio }

internal data class TvPickerItem(
    val label: String,
    val detail: String? = null,
    val selected: Boolean = false,
    val leadingIcon: ImageVector? = null,
    val frame: (@Composable () -> Unit)? = null,
)

private val FrameCardWidth = 200.dp

/**
 * A TV menu, opened under the transport as a row the remote walks with LEFT and RIGHT —
 * the direction every other row in the app runs. The selected entry starts focused, and
 * OK spends the press on the item.
 */
@Composable
internal fun TvPickerRow(
    title: String,
    items: List<TvPickerItem>,
    colors: PlayerColors,
    focusKeyPrefix: String,
    focusDecoration: ControlFocus,
    onSelect: (Int) -> Unit,
) {
    val requesters = remember(items.size) { List(items.size) { FocusRequester() } }
    // One frame in, so the requesters are attached before the ask: a request to a
    // detached requester is dropped, and the row is composed fresh when it opens.
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { requesters[items.indexOfFirst { it.selected }.coerceAtLeast(0)].requestFocus() }
    }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
            color = colors.primaryFaint,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                // Room for the focused card's glow, which draws outside the card.
                .padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            items.forEachIndexed { idx, item ->
                PickerCard(
                    item = item,
                    colors = colors,
                    modifier = Modifier
                        .focusRequester(requesters[idx])
                        .then(focusDecoration("$focusKeyPrefix.$idx", 10f, 1.05f))
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key in ActivateKeys) {
                                onSelect(idx)
                                true
                            } else {
                                false
                            }
                        }
                        .clickable { onSelect(idx) },
                )
            }
        }
    }
}

@Composable
private fun PickerCard(item: TvPickerItem, colors: PlayerColors, modifier: Modifier) {
    val shape = RoundedCornerShape(10.dp)
    val border = if (item.selected) colors.accent else Color.Transparent
    Column(
        modifier = modifier
            .then(if (item.frame != null) Modifier.width(FrameCardWidth) else Modifier.widthIn(min = 120.dp, max = 280.dp))
            .clip(shape)
            .background(colors.menuBackground.copy(alpha = 0.92f))
            .background(border.copy(alpha = 0.18f)),
    ) {
        item.frame?.let { frame ->
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black),
                contentAlignment = Alignment.Center,
            ) { frame() }
        }
        Row(
            modifier = Modifier.height(if (item.frame != null) 64.dp else 56.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when {
                item.selected -> Icon(Icons.Outlined.Check, "Selected", tint = colors.accent, modifier = Modifier.size(20.dp))
                item.leadingIcon != null -> Icon(item.leadingIcon, null, tint = colors.primaryFaint, modifier = Modifier.size(20.dp))
            }
            Column {
                Text(
                    item.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (item.selected) colors.accent else colors.primary,
                    fontWeight = if (item.selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.detail != null) {
                    Text(item.detail, style = MaterialTheme.typography.labelMedium, color = colors.primaryFaint, maxLines = 1)
                }
            }
        }
    }
}
