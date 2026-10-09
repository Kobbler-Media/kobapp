package dev.kobbler.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.LiveChannel
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.CenterFocusedItem
import dev.kobbler.app.ui.components.KobChip
import dev.kobbler.app.ui.components.KobChipStyle
import dev.kobbler.app.ui.components.rememberCached
import dev.kobbler.app.ui.components.rememberTvFocusState
import dev.kobbler.app.ui.components.tvClickable
import dev.kobbler.app.ui.components.tvFocusGlow
import dev.kobbler.app.ui.dashboard.livetv.ChannelLogo
import dev.kobbler.app.ui.dashboard.livetv.LiveThumb
import dev.kobbler.app.ui.dashboard.livetv.hhmmRange
import dev.kobbler.app.ui.dashboard.livetv.parseLiveInstant
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup
import dev.kobbler.app.ui.components.Async

private val PanelWidth = 420.dp
private val PanelRowHeight = 92.dp
private val SheetRowHeight = 68.dp

/**
 * The channel switcher over a live stream: OK switches on the same surface, and nothing
 * about playback is owned here — [onPick] hands the channel to [switchLiveChannel], which
 * moves the existing session rather than rebuilding the player.
 *
 * On a remote it is a column against the right edge, which is the direction the viewer
 * pressed to open it: a bar across the bottom of the picture is a phone's shape and fits
 * three channels on a ten-foot screen. LEFT walks back out to the transport. A phone gets
 * the same rows as a sheet over the bottom of the player, where its thumb is.
 */
@Composable
fun LiveChannelRail(
    api: KobblerApi,
    currentChannelId: String?,
    accent: Color,
    formFactor: FormFactor,
    tunersInUse: Int,
    tunerCount: Int,
    modifier: Modifier = Modifier,
    onPick: (LiveChannel) -> Unit,
    onOpenGuide: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val colors = KobappTheme.colors
    val dimens = KobappTheme.dimens
    val phone = formFactor == FormFactor.Phone
    var favouritesOnly by remember { mutableStateOf(true) }

    val (state, _) = rememberCached("livetv-rail") { api.getLiveGuideNow(limit = 200) }
    val all = (state as? Async.Ready)?.value ?: emptyList()
    val loading = state is Async.Loading
    val favouriteCount = all.count { it.favourite }
    // Favourites is the useful default, but only when there are any — an empty rail
    // reads as a broken tuner.
    LaunchedEffect(favouriteCount) { if (favouriteCount == 0) favouritesOnly = false }
    val channels = remember(all, favouritesOnly) {
        if (favouritesOnly) all.filter { it.favourite } else all
    }

    val listState = rememberLazyListState()
    var focusedIndex by remember { mutableIntStateOf(-1) }
    val currentIndex = remember(channels, currentChannelId) {
        channels.indexOfFirst { it.id == currentChannelId }.takeIf { it >= 0 }
    }

    Column(
        modifier
            .then(if (phone) Modifier.fillMaxWidth() else Modifier.width(PanelWidth).fillMaxHeight())
            .background(colors.bg.copy(alpha = if (phone) 0.94f else 0.97f))
            .focusKeyGroup("channelrail")
            // The column owns UP and DOWN; only the axis that leaves it is intercepted.
            // On a phone the sheet is read by dragging, so nothing is.
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (phone) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft, Key.Back, Key.Escape -> { onDismiss(); true }
                    else -> false
                }
            }
            .padding(horizontal = dimens.s5, vertical = dimens.s4),
        verticalArrangement = Arrangement.spacedBy(dimens.s3),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.s3),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Channels", style = KobappTheme.type.h3, color = colors.textHi)
            KobChip(
                text = if (favouritesOnly) "Favourites $favouriteCount" else "All ${all.size}",
                style = KobChipStyle.AccentTint,
                accent = accent,
                onClick = { favouritesOnly = !favouritesOnly },
                modifier = Modifier.focusKey("rail.filter", entryEligible = false),
            )
            Spacer(Modifier.weight(1f))
            if (tunerCount > 0) {
                Text(
                    "$tunersInUse/$tunerCount",
                    style = KobappTheme.type.caption,
                    color = colors.textLo,
                )
            }
            // DOWN reads the column now, so the full guide is a control rather than a
            // key a viewer has to be told about.
            if (onOpenGuide != null) {
                KobChip(
                    text = "Full guide",
                    style = KobChipStyle.Default,
                    accent = accent,
                    onClick = onOpenGuide,
                    modifier = Modifier.focusKey("rail.guide", entryEligible = false),
                )
            }
        }

        if (loading && channels.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = accent, modifier = Modifier.size(28.dp))
            }
            return@Column
        }
        if (channels.isEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dimens.s2),
                modifier = Modifier.height(120.dp),
            ) {
                Icon(Icons.Outlined.Tv, null, tint = colors.textXlo, modifier = Modifier.size(20.dp))
                Text("No channels", style = KobappTheme.type.bodySm, color = colors.textLo)
            }
            return@Column
        }

        CenterFocusedItem(listState, focusedIndex, topRow = 0)
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(if (phone) dimens.s1 else dimens.s2),
            contentPadding = PaddingValues(vertical = KobappTheme.dimens.focusBleed),
            modifier = if (phone) {
                Modifier.fillMaxWidth().heightIn(max = 360.dp)
            } else {
                Modifier.fillMaxWidth().weight(1f)
            },
        ) {
            itemsIndexed(channels, key = { _, it -> it.id }) { index, channel ->
                ChannelRow(
                    channel = channel,
                    accent = accent,
                    current = channel.id == currentChannelId,
                    compact = phone,
                    preferredEntry = index == (currentIndex ?: 0),
                    onFocused = { focusedIndex = index },
                    onClick = { onPick(channel) },
                )
            }
        }
        LaunchedEffect(currentIndex) {
            currentIndex?.let { listState.scrollToItem(it) }
        }
    }
}

/**
 * One channel as the guide sees it: the still for what is on, how far through it is, and
 * what follows. The channel being watched carries the accent on every edge it has — it is
 * the one thing the panel has to answer at a glance.
 */
@Composable
private fun ChannelRow(
    channel: LiveChannel,
    accent: Color,
    current: Boolean,
    compact: Boolean,
    preferredEntry: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    val colors = KobappTheme.colors
    val focusState = rememberTvFocusState()
    val thumbWidth: Dp = if (compact) 60.dp else 92.dp
    val thumbHeight: Dp = if (compact) 34.dp else 52.dp
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 11.dp else 13.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(if (compact) SheetRowHeight else PanelRowHeight)
            .focusKey("ch.${channel.id}", preferredEntry = preferredEntry)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .tvFocusGlow(focusState, cornerRadiusDp = 12f, scale = 1.01f)
            .clip(RoundedCornerShape(12.dp))
            .background(if (current) lerp(colors.elev2, accent, 0.16f) else colors.elev1)
            .border(if (current) 2.dp else 1.dp, if (current) accent else colors.lineSoft, RoundedCornerShape(12.dp))
            .tvClickable(focusState, onClick)
            .padding(horizontal = if (compact) 12.dp else 13.dp),
    ) {
        Text(
            channel.lcn ?: "",
            style = KobappTheme.type.caption.copy(fontWeight = FontWeight.Bold),
            color = if (current) accent else colors.textMd,
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.width(24.dp),
        )
        ChannelLogo(channel, accent, if (compact) 34.dp else 40.dp)
        // The still, not a coloured bar: the guide everywhere else in the app leads with
        // programme artwork, and this row is the same guide seen from inside the player.
        LiveThumb(
            channel.now?.imageUrl ?: channel.now?.posterUrl,
            width = thumbWidth,
            height = thumbHeight,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    channel.name,
                    style = KobappTheme.type.labelSm,
                    color = if (current) accent else colors.textLo,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (current) WatchingTag(accent)
            }
            Text(
                channel.now?.title ?: "No guide data",
                style = KobappTheme.type.caption.copy(fontWeight = FontWeight.SemiBold),
                color = if (current) colors.textHi else colors.textMd,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                nowLine(channel),
                style = KobappTheme.type.labelSm,
                color = colors.textLo,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // "What is on after this" is the other half of the decision, and the rail was
            // the one guide surface that never showed it.
            if (!compact) {
                channel.next?.title?.let { nextTitle ->
                    Text(
                        "Next · $nextTitle",
                        style = KobappTheme.type.labelSm,
                        color = colors.textXlo,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            ProgressBar(channel, if (current) accent else colors.textXlo)
        }
        if (compact) {
            Text(
                minutesLeftLabel(channel),
                style = KobappTheme.type.labelSm,
                color = colors.textLo,
            )
        }
    }
}

@Composable
private fun WatchingTag(accent: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(accent)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text("WATCHING", style = KobappTheme.type.labelSm, color = KobappTheme.colors.bg)
    }
}

@Composable
private fun ProgressBar(channel: LiveChannel, tint: Color) {
    val colors = KobappTheme.colors
    val pct = channel.now?.progressPct
    Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(colors.elev3)) {
        if (pct != null) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth((pct / 100f).coerceIn(0f, 1f))
                    .clip(RoundedCornerShape(2.dp))
                    .background(tint),
            )
        }
    }
}

private fun nowLine(channel: LiveChannel): String {
    val now = channel.now ?: return ""
    return listOfNotNull(
        hhmmRange(now.startTime, now.endTime).takeIf { it.isNotEmpty() },
        minutesLeftLabel(channel).takeIf { it.isNotEmpty() },
    ).joinToString(" · ")
}

private fun minutesLeftLabel(channel: LiveChannel): String {
    val left = channel.minutesLeft
        ?: channel.now?.endTime?.let { end ->
            val endMs = parseLiveInstant(end)?.toEpochMilliseconds() ?: return ""
            ((endMs - kotlin.time.Clock.System.now().toEpochMilliseconds()) / 60_000L).toInt()
        }
        ?: return ""
    return if (left <= 0) "ending" else "$left min"
}
