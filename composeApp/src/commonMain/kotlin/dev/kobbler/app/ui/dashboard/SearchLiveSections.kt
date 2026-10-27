package dev.kobbler.app.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
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
import dev.kobbler.app.data.IptvChannel
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.LiveChannel
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.tvAutoGlow
import dev.kobbler.app.ui.dashboard.livetv.ChannelLogo
import dev.kobbler.app.ui.dashboard.livetv.GuideFutureHours
import dev.kobbler.app.ui.dashboard.livetv.LiveBadge
import dev.kobbler.app.ui.dashboard.livetv.LiveGuideSelection
import dev.kobbler.app.ui.dashboard.livetv.isAiringAt
import dev.kobbler.app.ui.dashboard.livetv.isLocalToday
import dev.kobbler.app.ui.dashboard.livetv.localDayLabel
import dev.kobbler.app.ui.dashboard.livetv.localHhMm
import dev.kobbler.app.ui.dashboard.livetv.nowInstant
import dev.kobbler.app.ui.dashboard.livetv.parseLiveInstant
import dev.kobbler.kobfocus.focusKey
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.time.Duration.Companion.hours

data class LiveSearchResults(val channels: List<LiveChannel>, val airings: List<LiveGuideSelection>)

private const val LiveChannelHits = 5
private const val IptvChannelHits = 8

suspend fun searchIptv(api: KobblerApi, query: String): List<IptvChannel> =
    runCatching { api.getIptvChannels(search = query, limit = IptvChannelHits).channels }.getOrDefault(emptyList())

internal fun LazyGridScope.iptvChannelRows(channels: List<IptvChannel>, onClick: (IptvChannel) -> Unit) {
    items(channels, key = { "iptv-ch-${it.id}" }) { channel ->
        ResultRow(
            key = "iptv.ch.${channel.id}",
            logo = { ChannelIcon(channel.iconUrl, channel.name, KobappTheme.colors.accIptv, 42.dp) },
            title = channel.name,
            subtitle = listOfNotNull(channel.category, channel.language?.uppercase()).joinToString(" · "),
            onAir = false,
            onClick = { onClick(channel) },
        )
    }
}

suspend fun searchLive(api: KobblerApi, query: String): LiveSearchResults = coroutineScope {
    val now = nowInstant()
    val channels = async {
        runCatching { api.getLiveChannels(search = query, withNow = true, limit = LiveChannelHits).channels }.getOrDefault(emptyList())
    }
    val guide = async {
        runCatching {
            api.getLiveGuide(from = now.toString(), to = (now + GuideFutureHours.hours).toString(), search = query)
        }.getOrDefault(emptyList())
    }
    LiveSearchResults(
        channels = channels.await(),
        airings = guide.await()
            .flatMap { row -> row.entries.map { LiveGuideSelection(row.channel, it) } }
            .sortedBy { it.entry.startTime },
    )
}

internal fun LazyGridScope.liveChannelRows(channels: List<LiveChannel>, onClick: (LiveChannel) -> Unit) {
    items(channels, key = { "live-ch-${it.id}" }) { channel ->
        ResultRow(
            key = "live.ch.${channel.id}",
            logo = { ChannelLogo(channel, KobappTheme.colors.accLive, 42.dp) },
            title = channel.name,
            subtitle = channel.now?.title?.let { "Now: $it" } ?: channel.lcn?.let { "Channel $it" },
            onAir = false,
            onClick = { onClick(channel) },
        )
    }
}

internal fun LazyGridScope.liveAiringRows(visible: List<LiveGuideSelection>, onClick: (LiveGuideSelection) -> Unit) {
    val now = nowInstant()
    items(visible, key = { "live-air-${it.entry.id}" }) { airing ->
        val start = parseLiveInstant(airing.entry.startTime)
        val onAir = airing.entry.isAiringAt(now)
        val whenLabel = when {
            onAir -> null
            start == null -> null
            start.isLocalToday() -> start.localHhMm()
            else -> "${start.localDayLabel()} ${start.localHhMm()}"
        }
        ResultRow(
            key = "live.air.${airing.entry.id}",
            logo = { ChannelLogo(airing.channel, KobappTheme.colors.accLive, 42.dp) },
            title = airing.entry.title,
            subtitle = listOfNotNull(airing.channel.name, whenLabel, airing.entry.episodeNumber).joinToString(" · "),
            onAir = onAir,
            onClick = { onClick(airing) },
        )
    }
}

@Composable
private fun ResultRow(key: String, logo: @Composable () -> Unit, title: String, subtitle: String?, onAir: Boolean, onClick: () -> Unit) {
    val colors = KobappTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusKey(key)
            .tvAutoGlow(cornerRadiusDp = 10f, scale = 1f)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(vertical = 8.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        logo()
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = KobappTheme.type.body.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textHi,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = KobappTheme.type.bodySm, color = colors.textLo, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (onAir) LiveBadge("NOW", colors.accLive)
    }
}
