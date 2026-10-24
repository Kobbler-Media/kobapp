package dev.kobbler.app.ui.dashboard

import androidx.compose.foundation.background
import dev.kobbler.app.ui.scrollsUnderTopChrome
import dev.kobbler.app.ui.LocalTopChromeOverlap
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup
import dev.kobbler.app.data.IptvChannel
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.BrowseHeader
import dev.kobbler.app.ui.components.EmptyState
import dev.kobbler.app.ui.components.SectionHeading
import dev.kobbler.app.ui.components.tvAutoGlow

// The directory is thousands of rows on a full playlist. Pages keep the first paint cheap
// and each response small; the rest stream in behind it.
private const val ChannelPageSize = 100
private val ChannelPublishInterval = 1.seconds

private class ChannelDirectory(val count: Int, val groups: List<Pair<String, List<IptvChannel>>>) {
    companion object {
        val Empty = ChannelDirectory(0, emptyList())

        fun of(channels: List<IptvChannel>) = ChannelDirectory(
            channels.size,
            channels.groupBy { it.category?.takeIf { c -> c.isNotBlank() } ?: "Uncategorised" }
                .toList()
                .sortedBy { it.first },
        )
    }
}

@Composable
fun IptvChannelsDirectoryScreen(
    api: KobblerApi,
    modifier: Modifier = Modifier,
    formFactor: FormFactor = FormFactor.Desktop,
    onChannelClick: (String) -> Unit = {},
    showHeader: Boolean = true,
) {
    val colors = KobappTheme.colors
    val dimens = KobappTheme.dimens
    val accent = colors.accIptv
    val compact = formFactor == FormFactor.Phone
    val gutter = when (formFactor) {
        FormFactor.TV -> dimens.pageGutterTv
        FormFactor.Phone -> dimens.pageGutterPhone
        else -> dimens.pageGutter
    }
    var directory by remember { mutableStateOf(ChannelDirectory.Empty) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // Sequential rather than scroll-driven: the sections are sorted by category name, so
    // a page fetched on demand can insert a whole new heading above whatever the viewer
    // is looking at. Loading straight through settles the order once and for good.
    // Regrouping the whole directory on every page is quadratic over a 26k-channel
    // playlist, so it runs off the main thread and is published at most once a second.
    LaunchedEffect(Unit) {
        val fetched = ArrayList<IptvChannel>()
        var lastPublish = TimeSource.Monotonic.markNow()
        suspend fun publish() {
            directory = withContext(Dispatchers.Default) { ChannelDirectory.of(fetched) }
            lastPublish = TimeSource.Monotonic.markNow()
            loading = false
        }
        try {
            var offset = 0
            while (true) {
                val page = api.getIptvChannels(limit = ChannelPageSize, offset = offset)
                if (page.channels.isEmpty()) break
                fetched += page.channels
                offset += page.channels.size
                if (offset >= page.total) break
                if (directory.count == 0 || lastPublish.elapsedNow() >= ChannelPublishInterval) publish()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message
        }
        publish()
    }
    val grouped = directory.groups

    Column(modifier.fillMaxSize()) {
        if (showHeader) {
            Box(Modifier.padding(horizontal = gutter, vertical = dimens.s4)) {
                BrowseHeader(
                    title = "Channels",
                    accent = accent,
                    countLine = "${directory.count} channels · ${grouped.size} categories",
                    compact = compact,
                )
            }
        }
        when {
            loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = accent)
            }
            // A page that fails part way through leaves the ones already in hand on
            // screen; only a directory with nothing in it reads as an error.
            error != null && directory.count == 0 -> EmptyState(
                icon = Icons.Outlined.LiveTv,
                title = "Couldn't load channels",
                subtitle = error ?: "",
            )
            directory.count == 0 -> EmptyState(
                icon = Icons.Outlined.LiveTv,
                title = "No channels available",
                subtitle = "Configure an IPTV source on the server to see channels.",
            )
            // Flat, not a heading item plus one item holding the whole category: lazy
            // layout only windows top-level items, so a wrapping Column composes and
            // measures every channel in the frame its page arrives.
            else -> LazyColumn(
                contentPadding = PaddingValues(start = gutter, end = gutter, top = dimens.s2 + LocalTopChromeOverlap.current, bottom = dimens.s2),
                verticalArrangement = Arrangement.spacedBy(dimens.s1),
                modifier = Modifier.scrollsUnderTopChrome().fillMaxSize(),
            ) {
                grouped.forEach { (category, list) ->
                    item(key = "h-$category") {
                        SectionHeading(
                            title = category,
                            accent = accent,
                            modifier = Modifier.padding(vertical = dimens.s2),
                        )
                    }
                    items(list, key = { it.id }) { ch ->
                        ChannelDirRow(
                            channel = ch,
                            category = category,
                            accent = accent,
                            onClick = { onChannelClick(ch.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelDirRow(
    channel: IptvChannel,
    category: String,
    accent: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    val colors = KobappTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The group used to be the container the whole category was rendered in.
            // Flattening removed it, so the row carries the category itself — kobfocus
            // derives full keys by walking ancestors, and an earlier modifier in the same
            // chain is one, so the registered key is unchanged.
            .focusKeyGroup("category.$category")
            .focusKey("channel.${channel.id}")
            .tvAutoGlow(cornerRadiusDp = 10f, scale = 1.01f)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.elev1)
            .border(1.dp, colors.lineSoft, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(CircleShape).background(colors.elev2),
            contentAlignment = Alignment.Center,
        ) {
            if (channel.iconUrl != null) {
                AsyncImage(model = channel.iconUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Text(
                    channel.name.firstOrNull()?.uppercase() ?: "?",
                    color = accent,
                    style = KobappTheme.type.body.copy(fontWeight = FontWeight.SemiBold),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                channel.name,
                color = colors.textHi,
                style = KobappTheme.type.body.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = channel.nowPlaying?.title ?: channel.language?.uppercase() ?: ""
            if (sub.isNotBlank()) {
                Text(
                    sub,
                    color = colors.textLo,
                    style = KobappTheme.type.bodySm,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (channel.isFavourite) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
        }
    }
}
