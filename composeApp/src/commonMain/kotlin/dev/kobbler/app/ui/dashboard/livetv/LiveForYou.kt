package dev.kobbler.app.ui.dashboard.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.LiveChannel
import dev.kobbler.app.data.LiveOnNow
import dev.kobbler.app.data.LiveOnNowItem
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.rememberTvFocusState
import dev.kobbler.app.ui.components.tvClickable
import dev.kobbler.app.ui.components.tvFocusGlow
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup

private const val ForYouMax = 8
private val QuickPlayCardWidth = 236.dp

/**
 * What the viewer usually has on around now first — on air or about to start — then the
 * shows they keep coming back to that are on air. One card per channel: two picks on the
 * same channel are one press either way.
 */
internal fun forYouPicks(onNow: LiveOnNow?): List<LiveOnNowItem> {
    if (onNow == null) return emptyList()
    val byKey = onNow.groups.associateBy { it.key }
    return (byKey["usual"]?.items.orEmpty() + byKey["series"]?.items.orEmpty())
        .distinctBy { it.channel.id }
        .take(ForYouMax)
}

@Composable
internal fun LiveForYou(
    items: List<LiveOnNowItem>,
    accent: Color,
    gutter: Dp,
    onWatch: (LiveChannel) -> Unit,
    modifier: Modifier = Modifier,
) {
    QuickPlayStrip("For you", gutter, modifier) {
        items(items, key = { it.channel.id }) { item ->
            QuickPlayCard(
                focusKey = "foryou.${item.channel.id}",
                logo = { size -> ChannelLogo(item.channel, accent, size) },
                title = item.guide.title,
                channelName = item.channel.name,
                timing = item.startsInMinutes?.let { if (it <= 0) "starting now" else "in $it min" }
                    ?: item.minutesLeft?.let { if (it > 0) "$it min left" else null },
                upcoming = item.startsInMinutes != null,
                accent = accent,
                onClick = { onWatch(item.channel) },
            )
        }
    }
}

/** A labelled row of [QuickPlayCard]s above a guide list: the shortest way to something to watch. */
@Composable
internal fun QuickPlayStrip(label: String, gutter: Dp, modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            label,
            style = KobappTheme.type.labelSm,
            color = KobappTheme.colors.textLo,
            modifier = Modifier.padding(horizontal = gutter + 6.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = gutter),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.focusKeyGroup("quickplay"),
            content = content,
        )
    }
}

@Composable
internal fun QuickPlayCard(
    focusKey: String,
    logo: @Composable (Dp) -> Unit,
    title: String,
    channelName: String,
    timing: String?,
    upcoming: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val colors = KobappTheme.colors
    val focusState = rememberTvFocusState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .width(QuickPlayCardWidth)
            .focusKey(focusKey, entryEligible = false)
            .tvFocusGlow(focusState, cornerRadiusDp = 12f, scale = 1.02f)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.elev1)
            .border(1.dp, colors.lineSoft, RoundedCornerShape(12.dp))
            .tvClickable(focusState, onClick)
            .padding(10.dp),
    ) {
        logo(36.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = KobappTheme.type.bodySm.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textHi,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    channelName,
                    style = KobappTheme.type.caption,
                    color = colors.textLo,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (timing != null) {
                    Text(
                        timing,
                        style = KobappTheme.type.caption.copy(fontWeight = FontWeight.SemiBold),
                        color = if (upcoming) accent else colors.textLo,
                        maxLines = 1,
                    )
                }
            }
        }
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(999.dp)).background(accent), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.PlayArrow, contentDescription = "Play $title", tint = colors.bg, modifier = Modifier.size(18.dp))
        }
    }
}
