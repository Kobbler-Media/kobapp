package dev.kobbler.app.ui.detail

import dev.kobbler.app.ui.kobTopInset
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.kobbler.app.data.IptvChannel
import dev.kobbler.app.data.IptvEpgEntry
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.StripedBackdrop
import dev.kobbler.app.ui.player.GlobalPlayerState
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobplayer.Player
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.launch

@Composable
fun IptvChannelDetailScreen(
    channel: IptvChannel,
    epg: List<IptvEpgEntry>,
    api: KobblerApi,
    player: Player?,
    gps: GlobalPlayerState,
    onBack: () -> Unit,
    formFactor: FormFactor = FormFactor.Desktop,
) {
    val colors = KobappTheme.colors
    val accent = colors.accIptv
    val scope = rememberCoroutineScope()
    var isFavourite by remember(channel) { mutableStateOf(channel.isFavourite) }
    var favouriteId by remember(channel) { mutableStateOf(channel.favouriteId) }
    val isLoading = gps.isPreparing && gps.mediaId == channel.id
    val gutter = gutterFor(formFactor)
    val isPhone = formFactor == FormFactor.Phone

    val now = channel.nowPlaying
    val upcoming = remember(epg, now) {
        val anchor = now?.id
        if (anchor == null) epg.take(5)
        else epg.dropWhile { it.id != anchor }.drop(1).take(5)
    }

    val watchLive: () -> Unit = {
        startWatch(channel, player, gps, api, scope)
    }
    val toggleFavourite: () -> Unit = {
        scope.launch {
            try {
                if (isFavourite && favouriteId != null) {
                    api.removeIptvFavourite(favouriteId!!)
                    isFavourite = false
                    favouriteId = null
                } else {
                    api.addIptvFavourite(channel.id)
                    isFavourite = true
                }
            } catch (_: Exception) {}
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (isPhone) {
            PhoneLayout(
                channel = channel, accent = accent, gutter = gutter,
                now = now, upcoming = upcoming, schedule = epg,
                isFavourite = isFavourite, isLoading = isLoading,
                onWatchLive = watchLive, onToggleFavourite = toggleFavourite,
            )
        } else {
            WideLayout(
                channel = channel, accent = accent, gutter = gutter,
                formFactor = formFactor, now = now, upcoming = upcoming,
                schedule = epg, isFavourite = isFavourite, isLoading = isLoading,
                onWatchLive = watchLive, onToggleFavourite = toggleFavourite,
            )
        }

        DetailBackButton(onBack = onBack, formFactor = formFactor, accent = accent)
    }
}

@Composable
private fun WideLayout(
    channel: IptvChannel,
    accent: Color,
    gutter: Dp,
    formFactor: FormFactor,
    now: IptvEpgEntry?,
    upcoming: List<IptvEpgEntry>,
    schedule: List<IptvEpgEntry>,
    isFavourite: Boolean,
    isLoading: Boolean,
    onWatchLive: () -> Unit,
    onToggleFavourite: () -> Unit,
) {
    val isTv = formFactor == FormFactor.TV
    val pageScroll = rememberScrollState()
    Column(Modifier.fillMaxSize().scrollWhenFocusIsStuck(pageScroll).verticalScroll(pageScroll)) {
        ChannelHero(
            channel = channel, accent = accent, gutter = gutter, formFactor = formFactor,
            isFavourite = isFavourite, isLoading = isLoading,
            onWatchLive = onWatchLive, onToggleFavourite = onToggleFavourite,
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(if (isTv) 48.dp else 36.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1.4f)) {
                if (now != null) {
                    OnNowSection(entry = now, accent = accent, formFactor = formFactor)
                    Spacer(Modifier.height(if (isTv) 36.dp else 28.dp))
                }
                if (upcoming.isNotEmpty()) {
                    NextUpSection(entries = upcoming)
                }
            }

            if (schedule.isNotEmpty()) {
                TodaySchedule(
                    entries = schedule,
                    accent = accent,
                    activeId = now?.id,
                    modifier = Modifier
                        .weight(1f)
                        .widthIn(min = 240.dp)
                        .heightIn(max = 720.dp),
                )
            }
        }

        Spacer(Modifier.height(48.dp))
    }
}

@Composable
private fun PhoneLayout(
    channel: IptvChannel,
    accent: Color,
    gutter: Dp,
    now: IptvEpgEntry?,
    upcoming: List<IptvEpgEntry>,
    schedule: List<IptvEpgEntry>,
    isFavourite: Boolean,
    isLoading: Boolean,
    onWatchLive: () -> Unit,
    onToggleFavourite: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item {
            ChannelHero(
                channel = channel, accent = accent, gutter = gutter, formFactor = FormFactor.Phone,
                isFavourite = isFavourite, isLoading = isLoading,
                onWatchLive = onWatchLive, onToggleFavourite = onToggleFavourite,
            )
            Spacer(Modifier.height(20.dp))
        }
        if (now != null) {
            item {
                Box(Modifier.padding(horizontal = gutter)) {
                    OnNowSection(entry = now, accent = accent, formFactor = FormFactor.Phone)
                }
                Spacer(Modifier.height(20.dp))
            }
        }
        if (upcoming.isNotEmpty()) {
            item {
                Box(Modifier.padding(horizontal = gutter)) {
                    NextUpSection(entries = upcoming)
                }
                Spacer(Modifier.height(20.dp))
            }
        }
        if (schedule.isNotEmpty()) {
            item {
                Box(Modifier.padding(horizontal = gutter)) {
                    SectionLabel("Today's schedule", accent = KobappTheme.colors.textLo)
                    Spacer(Modifier.height(8.dp))
                }
            }
            items(schedule, key = { it.id }) { entry ->
                Box(Modifier.padding(horizontal = gutter)) {
                    ScheduleRow(entry = entry, accent = accent, active = entry.id == now?.id)
                }
            }
        }
    }
}

@Composable
private fun ChannelHero(
    channel: IptvChannel,
    accent: Color,
    gutter: Dp,
    formFactor: FormFactor,
    isFavourite: Boolean,
    isLoading: Boolean,
    onWatchLive: () -> Unit,
    onToggleFavourite: () -> Unit,
) {
    val colors = KobappTheme.colors
    val isPhone = formFactor == FormFactor.Phone
    val heroHeight = when (formFactor) {
        FormFactor.TV -> 300.dp
        FormFactor.Desktop -> 360.dp
        FormFactor.Phone -> 280.dp
    }
    val logoSize = when (formFactor) {
        FormFactor.TV -> 120.dp
        FormFactor.Desktop -> 120.dp
        FormFactor.Phone -> 88.dp
    }

    Box(Modifier.fillMaxWidth().height(heroHeight)) {
        StripedBackdrop(
            accent = accent,
            opacity = 0.40f,
            withRadialOverlay = false,
            modifier = Modifier.matchParentSize(),
            cached = true,
        )
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0.0f to Color.Transparent,
                    0.6f to colors.bg.copy(alpha = 0.55f),
                    1.0f to colors.bg,
                ),
            ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = gutter, end = gutter, top = kobTopInset + 70.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            val crumb = buildString {
                append("Live TV · channel")
                if (!channel.category.isNullOrBlank()) append(" · ${channel.category}")
                if (!channel.country.isNullOrBlank()) append(" · ${channel.country}")
            }
            Text(
                crumb.uppercase(),
                style = KobappTheme.type.bodySm.copy(letterSpacing = 0.12.em),
                color = colors.textLo,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(if (isPhone) 14.dp else 18.dp))

            if (isPhone) {
                ChannelLogo(channel = channel, size = logoSize, accent = accent)
                Spacer(Modifier.height(14.dp))
                ChannelTitle(channel.name, colors.textHi)
                Spacer(Modifier.height(14.dp))
                HeroActions(
                    accent = accent, isFavourite = isFavourite, isLoading = isLoading,
                    onWatchLive = onWatchLive, onToggleFavourite = onToggleFavourite,
                    wide = false,
                )
            } else {
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    ChannelLogo(channel = channel, size = logoSize, accent = accent)
                    Column(modifier = Modifier.padding(bottom = 4.dp)) {
                        ChannelTitle(channel.name, colors.textHi)
                        val meta = listOfNotNull(channel.language, channel.country)
                            .joinToString(" · ")
                        if (meta.isNotBlank()) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                meta,
                                style = KobappTheme.type.bodySm,
                                color = colors.textMd,
                            )
                        }
                        Spacer(Modifier.height(18.dp))
                        HeroActions(
                            accent = accent, isFavourite = isFavourite, isLoading = isLoading,
                            onWatchLive = onWatchLive, onToggleFavourite = onToggleFavourite,
                            wide = true,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelTitle(name: String, color: Color) {
    Text(
        name,
        style = KobappTheme.displayTitle.copy(
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Medium,
            letterSpacing = (-0.025).em,
        ),
        color = color,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ChannelLogo(channel: IptvChannel, size: Dp, accent: Color) {
    val colors = KobappTheme.colors
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.linearGradient(listOf(accent, lerp(accent, colors.elev3, 0.7f))),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (!channel.iconUrl.isNullOrBlank()) {
            AsyncImage(
                model = channel.iconUrl,
                contentDescription = channel.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(size * 0.12f),
            )
        } else {
            Text(
                channel.name.firstOrNull()?.uppercase() ?: "?",
                style = KobappTheme.type.bodySm.copy(
                    fontSize = (size.value * 0.42f).sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = colors.bg,
            )
        }
    }
}

@Composable
private fun HeroActions(
    accent: Color,
    isFavourite: Boolean,
    isLoading: Boolean,
    onWatchLive: () -> Unit,
    onToggleFavourite: () -> Unit,
    wide: Boolean,
) {
    DetailActionRow(
        wide = wide,
        primary = { fill ->
            PrimaryAction(
                icon = Icons.Outlined.PlayArrow,
                label = if (isLoading) "Loading…" else "Watch live",
                onClick = onWatchLive,
                enabled = !isLoading,
                modifier = Modifier.focusKey("action.watchLive", preferredEntry = true).then(fill),
            )
        },
    ) {
        IconAction(
            icon = if (isFavourite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
            label = if (isFavourite) "Favourited" else "Favourite",
            tint = if (isFavourite) accent else KobappTheme.colors.textHi,
            accent = accent,
            onClick = onToggleFavourite,
            modifier = Modifier.focusKey("action.favourite"),
        )
    }
}

@Composable
private fun OnNowSection(entry: IptvEpgEntry, accent: Color, formFactor: FormFactor) {
    val colors = KobappTheme.colors
    val isTv = formFactor == FormFactor.TV
    val progress = liveProgress(entry.startTime, entry.stopTime)
    val startHm = formatEpgTime(entry.startTime)
    val endHm = formatEpgTime(entry.stopTime)
    val nowHm = formatEpgTime(currentIsoTime())
    val untilLabel = "On now · until $endHm"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.elev1)
            .border(1.dp, colors.lineSoft, RoundedCornerShape(12.dp))
            .padding(if (isTv) 22.dp else 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LivePill(accent)
            Spacer(Modifier.width(10.dp))
            Text(
                untilLabel.uppercase(),
                style = KobappTheme.type.bodySm.copy(letterSpacing = 0.12.em),
                color = accent,
            )
        }
        Spacer(Modifier.height(14.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .width(if (isTv) 220.dp else 180.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp)),
            ) {
                StripedBackdrop(
                    accent = accent,
                    opacity = 0.55f,
                    withRadialOverlay = false,
                    modifier = Modifier.matchParentSize(),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entry.title ?: "Untitled",
                    style = KobappTheme.type.readerTitle,
                    color = colors.textHi,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "$startHm — $endHm",
                    style = KobappTheme.type.bodySm,
                    color = colors.textMd,
                )
                if (!entry.description.isNullOrBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        entry.description,
                        style = KobappTheme.type.bodySm,
                        color = colors.textMd,
                        maxLines = if (isTv) 5 else 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(14.dp))
                LiveProgressBar(progress = progress, accent = accent)
                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        startHm,
                        style = KobappTheme.type.bodySm,
                        color = colors.textLo,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "$nowHm · live",
                        style = KobappTheme.type.bodySm.copy(fontWeight = FontWeight.SemiBold),
                        color = accent,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        endHm,
                        style = KobappTheme.type.bodySm,
                        color = colors.textLo,
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveProgressBar(progress: Float, accent: Color) {
    val colors = KobappTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(colors.elev2),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(3.dp)
                .background(accent),
        )
    }
}

@Composable
private fun LivePill(accent: Color) {
    Box(
        modifier = Modifier
            .clip(KobappTheme.shapes.pill)
            .background(accent)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            "LIVE",
            color = KobappTheme.colors.bg,
            style = KobappTheme.type.labelSm.copy(fontWeight = FontWeight.Bold),
        )
    }
}

@Composable
private fun NextUpSection(entries: List<IptvEpgEntry>) {
    val colors = KobappTheme.colors
    Column(Modifier.fillMaxWidth()) {
        SectionLabel("Next up", accent = colors.textLo)
        Spacer(Modifier.height(6.dp))
        entries.forEachIndexed { idx, entry ->
            NextUpRow(entry = entry)
            if (idx < entries.size - 1) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.lineSoft))
            }
        }
    }
}

@Composable
private fun NextUpRow(entry: IptvEpgEntry) {
    val colors = KobappTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            formatEpgTime(entry.startTime),
            style = KobappTheme.type.bodySm,
            color = colors.textMd,
            modifier = Modifier.width(64.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.title ?: "Untitled",
                style = KobappTheme.type.bodySm.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textHi,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!entry.description.isNullOrBlank()) {
                Text(
                    entry.description,
                    style = KobappTheme.type.bodySm,
                    color = colors.textLo,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            durationLabel(entry.startTime, entry.stopTime),
            style = KobappTheme.type.bodySm,
            color = colors.textLo,
            maxLines = 1,
            modifier = Modifier.width(72.dp),
        )
    }
}

@Composable
private fun TodaySchedule(
    entries: List<IptvEpgEntry>,
    accent: Color,
    activeId: String?,
    modifier: Modifier = Modifier,
) {
    val colors = KobappTheme.colors
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, colors.lineSoft, RoundedCornerShape(12.dp))
            .background(colors.elev1)
            .padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
    ) {
        SectionLabel("Today's schedule", accent = colors.textLo)
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(end = 8.dp, bottom = 8.dp),
        ) {
            items(entries, key = { it.id }) { entry ->
                ScheduleRow(entry = entry, accent = accent, active = entry.id == activeId)
            }
        }
    }
}

@Composable
private fun ScheduleRow(entry: IptvEpgEntry, accent: Color, active: Boolean) {
    val colors = KobappTheme.colors
    val bg = if (active) accent.copy(alpha = 0.10f) else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = if (active) 10.dp else 0.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            formatEpgTime(entry.startTime),
            style = KobappTheme.type.bodySm.copy(
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            ),
            color = if (active) accent else colors.textMd,
            modifier = Modifier.width(56.dp),
        )
        Text(
            entry.title ?: "Untitled",
            style = KobappTheme.type.bodySm.copy(
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            ),
            color = if (active) colors.textHi else colors.textMd,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(colors.lineSoft),
    )
}

@Composable
private fun SectionLabel(text: String, accent: Color) {
    Text(
        text.uppercase(),
        style = KobappTheme.type.bodySm.copy(letterSpacing = 0.12.em),
        color = accent,
    )
}

private fun startWatch(
    channel: IptvChannel,
    player: Player?,
    gps: GlobalPlayerState,
    api: KobblerApi,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    if (player == null || gps.isPreparing) return

    gps.sessionId?.let { sid ->
        scope.launch { api.closeSession(sid) }
    }
    gps.sessionId = null
    gps.mediaType = "iptv"
    gps.mediaId = channel.id
    gps.title = channel.name
    gps.subtitle = channel.nowPlaying?.title
    gps.posterUrl = channel.iconUrl
    gps.logoUrl = null
    gps.detailDestination = null
    gps.chapters = emptyList()
    gps.skipSegments = emptyList()
    gps.thumbnailsUrl = null
    gps.lufs = null
    gps.serverAudioTracks = emptyList()
    gps.selectedServerAudioIndex = null
    gps.serverSubtitleTracks = emptyList()
    gps.selectedServerSubtitleTrackId = null
    gps.isLive = true
    gps.loadingError = null
    gps.isPreparing = true
    gps.preparingNote = "Probing live sources — this can take up to a minute."
    gps.isFullscreen = true

    scope.launch {
        try {
            val session = api.playIptvChannel(channel.id)
            if (gps.mediaId != channel.id || !gps.isPreparing) return@launch

            val relUrl = session.playlistUrl ?: session.directUrl
                ?: throw IllegalStateException("Server returned no stream URL (session_id=${session.sessionId})")
            val url = if (relUrl.startsWith("http://") || relUrl.startsWith("https://")) relUrl
                      else "${api.apiUrl}$relUrl"

            gps.load(
                player = player, api = api,
                type = "iptv", id = channel.id,
                streamUrl = url, newSessionId = session.sessionId,
                newTitle = channel.name, newSubtitle = channel.nowPlaying?.title,
                newPosterUrl = channel.iconUrl, newLogoUrl = null,
                destination = null,
            )
            gps.playUnmuted(player)
        } catch (e: Exception) {
            if (gps.mediaId == channel.id) {
                gps.failWith(e, listOf(
                    "Channel: ${channel.name}",
                    "ID: ${channel.id}",
                    "Endpoint: ${api.apiUrl}/api/v1/admin/iptv/sessions",
                ))
            }
        } finally {
            if (gps.mediaId == channel.id) {
                gps.isPreparing = false
                gps.preparingNote = null
            }
        }
    }
}

private fun formatEpgTime(iso: String): String {
    val tIdx = iso.indexOf('T')
    if (tIdx < 0) return iso
    return iso.substring(tIdx + 1).take(5)
}

private fun currentIsoTime(): String = Clock.System.now().toString()

private fun liveProgress(startIso: String, stopIso: String): Float {
    val start = parseInstant(startIso) ?: return 0f
    val stop = parseInstant(stopIso) ?: return 0f
    val nowMs = Clock.System.now().toEpochMilliseconds()
    val startMs = start.toEpochMilliseconds()
    val stopMs = stop.toEpochMilliseconds()
    if (stopMs <= startMs) return 0f
    return ((nowMs - startMs).toFloat() / (stopMs - startMs).toFloat()).coerceIn(0f, 1f)
}

private fun durationLabel(startIso: String, stopIso: String): String {
    val start = parseInstant(startIso) ?: return ""
    val stop = parseInstant(stopIso) ?: return ""
    val mins = ((stop.toEpochMilliseconds() - start.toEpochMilliseconds()) / 60_000L).toInt()
    if (mins <= 0) return ""
    if (mins < 60) return "${mins}m"
    val h = mins / 60
    val m = mins % 60
    return if (m == 0) "${h}h" else "${h}h${m}m"
}

private fun parseInstant(iso: String): Instant? = try {
    Instant.parse(iso)
} catch (_: Exception) {
    null
}
