package dev.kobbler.app.ui.dashboard.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.rememberTvFocusState
import dev.kobbler.app.ui.components.tvClickable
import dev.kobbler.app.ui.components.tvFocusGlow
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup
import kotlin.time.Instant

private val WideNameWidth = 190.dp

/** One programme in a channel row, whichever feed it came from. */
@Immutable
data class GuideSlot(val title: String, val start: Instant?, val end: Instant?)

/**
 * A channel in a guide list: what is on and what follows, read across on a wide screen and
 * stacked on a phone. Shared by Live TV and IPTV so the two pages scan the same way.
 */
@Composable
internal fun ChannelGuideRow(
    name: String,
    logo: @Composable (Dp) -> Unit,
    current: GuideSlot?,
    following: List<GuideSlot>,
    showProgress: Boolean,
    clock: GuideClock,
    wide: Boolean,
    accent: Color,
    groupKey: String,
    rowKey: String,
    preferredEntry: Boolean,
    emptyLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badges: @Composable RowScope.() -> Unit = {},
    actions: @Composable RowScope.() -> Unit,
) {
    val colors = KobappTheme.colors
    val focusState = rememberTvFocusState()
    // Actions are siblings of the row body, not children: a focusable ancestor swallows a
    // D-pad press aimed at its own descendants.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (wide) 14.dp else 10.dp),
        modifier = modifier.fillMaxWidth().focusKeyGroup(groupKey),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (wide) 14.dp else 10.dp),
            modifier = Modifier
                .weight(1f)
                .focusKey(rowKey, preferredEntry = preferredEntry)
                .tvFocusGlow(focusState, cornerRadiusDp = 12f, scale = 1.01f)
                .clip(RoundedCornerShape(12.dp))
                .tvClickable(focusState, onClick)
                .padding(vertical = if (wide) 10.dp else 9.dp, horizontal = 6.dp),
        ) {
            logo(if (wide) 44.dp else 38.dp)
            if (wide) {
                ChannelName(name, Modifier.width(WideNameWidth), badges)
                SlotNow(current, emptyLabel, showProgress, clock, accent, Modifier.weight(1.5f))
                following.take(2).forEach { SlotLater(it, Modifier.weight(1f)) }
                repeat(2 - following.take(2).size) { Box(Modifier.weight(1f)) }
            } else {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ChannelName(name, badges = badges)
                    SlotNow(current, emptyLabel, showProgress, clock, accent)
                    following.firstOrNull()?.let { next ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                next.start?.localHhMm() ?: "",
                                style = KobappTheme.type.caption.copy(fontWeight = FontWeight.SemiBold),
                                color = colors.textLo,
                            )
                            Text(next.title, style = KobappTheme.type.caption, color = colors.textLo, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        actions()
    }
}

@Composable
private fun ChannelName(name: String, modifier: Modifier = Modifier, badges: @Composable RowScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = modifier) {
        Text(
            name,
            style = KobappTheme.type.caption.copy(fontWeight = FontWeight.SemiBold),
            color = KobappTheme.colors.textMd,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        badges()
    }
}

@Composable
private fun SlotNow(slot: GuideSlot?, emptyLabel: String, showProgress: Boolean, clock: GuideClock, accent: Color, modifier: Modifier = Modifier) {
    val colors = KobappTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            slot?.title ?: emptyLabel,
            style = if (slot == null) KobappTheme.type.bodySm else KobappTheme.type.body.copy(fontWeight = FontWeight.SemiBold),
            color = if (slot == null) colors.textLo else colors.textHi,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val start = slot?.start
        val end = slot?.end
        if (slot == null || start == null || end == null) return@Column
        if (showProgress) {
            val fraction by remember(slot, clock) {
                derivedStateOf {
                    val total = (end - start).inWholeSeconds.coerceAtLeast(1)
                    ((clock.now - start).inWholeSeconds.toFloat() / total).coerceIn(0f, 1f)
                }
            }
            val left by remember(slot, clock) { derivedStateOf { minutesLabel((end - clock.now).inWholeSeconds) } }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(colors.elev3)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).clip(RoundedCornerShape(2.dp)).background(accent))
                }
                if (left.isNotEmpty()) Text("$left left", style = KobappTheme.type.labelSm, color = colors.textLo, maxLines = 1)
            }
        } else {
            Text("${start.localHhMm()} – ${end.localHhMm()}", style = KobappTheme.type.caption, color = colors.textLo, maxLines = 1)
        }
    }
}

@Composable
private fun SlotLater(slot: GuideSlot, modifier: Modifier = Modifier) {
    val colors = KobappTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(slot.start?.localHhMm() ?: "", style = KobappTheme.type.caption.copy(fontWeight = FontWeight.SemiBold), color = colors.textLo)
        Text(slot.title, style = KobappTheme.type.bodySm, color = colors.textMd, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The round button at the end of a channel row. Never an entry target: the row is. */
@Composable
internal fun RowIconButton(
    icon: ImageVector,
    label: String,
    focusKey: String,
    wide: Boolean,
    onClick: (() -> Unit)?,
    tint: Color = KobappTheme.colors.textLo,
    iconSize: Dp = 14.dp,
    border: Color = KobappTheme.colors.lineSoft,
) {
    val focusState = rememberTvFocusState()
    Box(
        modifier = Modifier
            .size(if (wide) 40.dp else 36.dp)
            .focusKey(focusKey, entryEligible = false)
            .tvFocusGlow(focusState, cornerRadiusDp = 999f, scale = 1.08f)
            .clip(RoundedCornerShape(999.dp))
            .border(1.dp, border, RoundedCornerShape(999.dp))
            .then(if (onClick != null) Modifier.tvClickable(focusState, onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(iconSize))
    }
}
