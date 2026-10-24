package dev.kobbler.app.ui.dashboard.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.LiveClashNotice
import dev.kobbler.app.data.LiveOnNowItem
import dev.kobbler.app.data.UpNextReason
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.KobButton
import dev.kobbler.app.ui.components.KobButtonStyle
import dev.kobbler.app.ui.components.ReasonChip
import dev.kobbler.app.ui.components.rememberTvFocusState
import dev.kobbler.app.ui.components.tvClickable
import dev.kobbler.app.ui.components.tvFocusGlow
import dev.kobbler.kobfocus.focusKey
import kotlin.math.roundToInt

private val OnNowWideCard = 340.dp
private val OnNowCompactCard = 262.dp
private val WideCardHeight = 252.dp
private val CompactCardHeight = 186.dp

/** The groups whose ranking is a time of day rather than "this is on and you watch it". */
private val SlotGroupKeys = setOf("timeofday", "usual")

internal fun onNowGroupReason(groupKey: String): UpNextReason =
    if (groupKey in SlotGroupKeys) UpNextReason.Schedule else UpNextReason.Live

internal fun onNowGroupTag(groupKey: String): String =
    if (groupKey in SlotGroupKeys) "Usual slot" else "On now"

internal fun onNowTiming(item: LiveOnNowItem): String = listOfNotNull(
    hhmmRange(item.guide.startTime, item.guide.endTime).takeIf { it.isNotEmpty() },
    item.startsInMinutes?.let { if (it <= 0) "starting now" else "in $it min" },
    item.minutesLeft?.takeIf { it > 0 }?.let { "$it min left" },
    item.guide.episodeNumber,
    item.guide.genres.firstOrNull(),
).joinToString(" · ")

@Composable
internal fun ConfidenceDots(confidence: Float, accent: Color, dotSize: Dp = 5.dp) {
    val colors = KobappTheme.colors
    val filled = (confidence * 4f).roundToInt().coerceIn(1, 4)
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(4) { i ->
            Box(
                Modifier
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(if (i < filled) accent else colors.line),
            )
        }
    }
}

@Composable
private fun OnNowBanner(item: LiveOnNowItem, groupKey: String, accent: Color, height: Dp) {
    val colors = KobappTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .background(
                Brush.linearGradient(listOf(lerp(colors.elev2, accent, 0.22f), colors.bg)),
            ),
    ) {
        GuideArtwork(item.guide, Color.Transparent, Modifier.matchParentSize(), scrim = 0.40f)
        ReasonChip(
            reason = onNowGroupReason(groupKey),
            label = onNowGroupTag(groupKey),
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(colors.bg.copy(alpha = 0.55f))
                .padding(horizontal = 9.dp, vertical = 4.dp),
        ) {
            Box(Modifier.width(3.dp).height(13.dp).clip(RoundedCornerShape(2.dp)).background(accent))
            Text(
                item.channel.name,
                style = KobappTheme.type.caption.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textHi,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        OnNowProgress(item, accent, Modifier.align(Alignment.BottomCenter), 3.dp)
    }
}

@Composable
private fun OnNowProgress(item: LiveOnNowItem, accent: Color, modifier: Modifier = Modifier, thickness: Dp) {
    val colors = KobappTheme.colors
    val pct = ((item.guide.progressPct ?: 0f) / 100f).coerceIn(0f, 1f)
    Box(modifier.fillMaxWidth().height(thickness).background(colors.elev3)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(pct).background(accent))
    }
}

@Composable
internal fun OnNowReasonLine(item: LiveOnNowItem, accent: Color, maxLines: Int) {
    val colors = KobappTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        item.confidence?.let { ConfidenceDots(it, accent) }
        Text(
            item.reason,
            style = KobappTheme.type.caption,
            color = colors.textLo,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun OnNowCard(
    item: LiveOnNowItem,
    groupKey: String,
    wide: Boolean,
    accent: Color,
    focusKey: String,
    preferredEntry: Boolean,
    onWatch: () -> Unit,
) {
    val colors = KobappTheme.colors
    val focusState = rememberTvFocusState()
    Column(
        modifier = Modifier
            .width(if (wide) OnNowWideCard else OnNowCompactCard)
            .height(if (wide) WideCardHeight else CompactCardHeight)
            .focusKey(focusKey, preferredEntry = preferredEntry)
            .tvFocusGlow(focusState, cornerRadiusDp = 12f, scale = 1.03f)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.elev1)
            .border(1.dp, colors.lineSoft, RoundedCornerShape(12.dp))
            .tvClickable(focusState, onWatch),
    ) {
        OnNowBanner(item, groupKey, accent, if (wide) 96.dp else 78.dp)
        Column(
            verticalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.fillMaxWidth().weight(1f).padding(start = 12.dp, end = 12.dp, top = 11.dp, bottom = 12.dp),
        ) {
            Text(
                item.guide.title,
                style = KobappTheme.type.bodySm.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textHi,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                onNowTiming(item),
                style = KobappTheme.type.caption,
                color = colors.textMd,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val synopsis = item.guide.synopsis
            if (wide && !synopsis.isNullOrBlank()) {
                Text(
                    synopsis,
                    style = KobappTheme.type.caption,
                    color = colors.textMd,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.lineSoft))
            Spacer(Modifier.height(2.dp))
            OnNowReasonLine(item, accent, maxLines = 2)
        }
    }
}

@Composable
internal fun OnNowPhoneRow(
    item: LiveOnNowItem,
    accent: Color,
    focusKey: String,
    preferredEntry: Boolean,
    onWatch: () -> Unit,
) {
    val colors = KobappTheme.colors
    val focusState = rememberTvFocusState()
    Row(
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        modifier = Modifier
            .fillMaxWidth()
            .focusKey(focusKey, preferredEntry = preferredEntry)
            .tvFocusGlow(focusState, cornerRadiusDp = 11f, scale = 1.01f)
            .clip(RoundedCornerShape(11.dp))
            .background(colors.elev1)
            .border(1.dp, colors.lineSoft, RoundedCornerShape(11.dp))
            .tvClickable(focusState, onWatch)
            .padding(11.dp),
    ) {
        ChannelLogo(item.channel, accent, 44.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LiveBadge("NOW", accent)
                Text(
                    item.channel.name,
                    style = KobappTheme.type.caption,
                    color = colors.textLo,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                item.guide.title,
                style = KobappTheme.type.caption.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textHi,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                onNowTiming(item),
                style = KobappTheme.type.caption,
                color = colors.textMd,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            OnNowReasonLine(item, accent, maxLines = 1)
            OnNowProgress(item, accent, thickness = 2.dp)
        }
    }
}

@Composable
internal fun OnNowNotice(
    notice: LiveClashNotice,
    busy: Boolean,
    accent: Color,
    compact: Boolean,
    modifier: Modifier = Modifier,
    onIgnore: () -> Unit,
    onRecordAlternative: (String) -> Unit,
) {
    val colors = KobappTheme.colors
    val dimens = KobappTheme.dimens
    val start = parseLiveInstant(notice.guide.startTime)?.localHhMm()
    Column(
        verticalArrangement = Arrangement.spacedBy(dimens.s2),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(lerp(colors.elev1, colors.accNotif, 0.10f))
            .border(1.dp, lerp(colors.line, colors.accNotif, 0.35f), RoundedCornerShape(13.dp))
            .padding(dimens.s3),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.s3),
        ) {
            Icon(
                Icons.Outlined.Schedule,
                contentDescription = null,
                tint = colors.accNotif,
                modifier = Modifier.size(20.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (start != null) {
                        "${notice.guide.title} starts at $start and every tuner is busy"
                    } else {
                        "${notice.guide.title} is on and every tuner is busy"
                    },
                    style = KobappTheme.type.bodySm.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.textHi,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(notice.reason, style = KobappTheme.type.caption, color = colors.textMd)
            }
            if (!compact) {
                OnNowNoticeActions(notice, busy, accent, onIgnore, onRecordAlternative)
            }
        }
        if (compact) {
            Row(horizontalArrangement = Arrangement.spacedBy(dimens.s2)) {
                OnNowNoticeActions(notice, busy, accent, onIgnore, onRecordAlternative)
            }
        }
    }
}

@Composable
private fun OnNowNoticeActions(
    notice: LiveClashNotice,
    busy: Boolean,
    accent: Color,
    onIgnore: () -> Unit,
    onRecordAlternative: (String) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KobappTheme.dimens.s2),
    ) {
        KobButton(
            text = "Ignore",
            onClick = onIgnore,
            style = KobButtonStyle.Ghost,
            modifier = Modifier.focusKey("notice.ignore", entryEligible = false),
        )
        val alternative = notice.alternative
        if (alternative != null) {
            val at = parseLiveInstant(alternative.startTime)?.localHhMm()
            KobButton(
                text = if (at != null) "Record the $at" else "Record the later airing",
                onClick = { onRecordAlternative(alternative.id) },
                style = KobButtonStyle.Accent,
                accent = accent,
                enabled = !busy,
                modifier = Modifier.focusKey("notice.record", entryEligible = false),
            )
        }
    }
}
