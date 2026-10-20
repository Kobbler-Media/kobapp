package dev.kobbler.app.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.RecentStreamDto
import dev.kobbler.app.data.StreamOption
import dev.kobbler.app.ui.player.PLAYBACK_FAILED
import dev.kobbler.app.ui.player.PLAYBACK_WORKED
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.KobButton
import dev.kobbler.app.ui.components.KobButtonStyle
import dev.kobbler.app.ui.components.KobChip
import dev.kobbler.app.ui.components.KobChipStyle
import dev.kobbler.app.ui.components.rememberTvFocusState
import dev.kobbler.app.ui.components.tvClickable
import dev.kobbler.app.ui.components.tvFocusGlow
import dev.kobbler.app.ui.navigation.InterceptBack
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import dev.kobbler.app.ui.safeTopPadding
import dev.kobbler.app.ui.safeBottomPadding

@Composable
fun StreamOptionsOverlay(
    options: List<StreamOption>,
    errors: List<String>,
    loading: Boolean,
    onSelect: (StreamOption) -> Unit,
    onDismiss: () -> Unit,
    api: KobblerApi? = null,
    recentStreams: List<RecentStreamDto> = emptyList(),
    onPlayRecent: ((RecentStreamDto) -> Unit)? = null,
) {
    val colors = KobappTheme.colors
    val accent = colors.accMovies
    // Confirmed-instant only; the rest are unknown rather than known-uncached.
    val instantCount = options.count { it.cached }
    val provider = options.firstOrNull()?.debridProvider?.takeIf { it.isNotBlank() }
    val firstStreamFocus = remember { FocusRequester() }
    InterceptBack(enabled = true, onBack = onDismiss)

    androidx.compose.runtime.LaunchedEffect(options, recentStreams, loading) {
        if (!loading && (options.isNotEmpty() || recentStreams.isNotEmpty())) {
            delay(100)
            runCatching { firstStreamFocus.requestFocus() }
        }
    }

    Box(Modifier.focusKeyGroup("streamOptions").fillMaxSize().background(colors.bg)) {
        Column(Modifier.fillMaxSize().safeTopPadding()) {
            OverlayTopBar(onBack = onDismiss, accent = accent)

            errors.forEach { err ->
                Text(
                    err,
                    style = KobappTheme.type.bodySm,
                    color = colors.err,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeBottomPadding()
                    .padding(horizontal = 24.dp, vertical = 12.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.elev1.copy(alpha = 0.95f))
                    .border(1.dp, colors.line, RoundedCornerShape(16.dp))
                    .padding(18.dp),
            ) {
                StreamPickerHeader(
                    provider = provider,
                    instantCount = instantCount,
                    totalCount = options.size,
                )
                Spacer(Modifier.height(16.dp))

                when {
                    loading -> LoadingState()
                    options.isEmpty() && recentStreams.isEmpty() -> EmptyState()
                    else -> StreamList(
                        options = options,
                        recentStreams = recentStreams,
                        onSelect = onSelect,
                        onPlayRecent = onPlayRecent,
                        api = api,
                        accent = accent,
                        firstStreamFocus = firstStreamFocus,
                    )
                }
            }
        }
    }
}

@Composable
private fun OverlayTopBar(onBack: () -> Unit, accent: Color) {
    val colors = KobappTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
    ) {
        val backFocus = rememberTvFocusState()
        Box(
            modifier = Modifier
                .focusKey("back")
                .size(40.dp)
                .clip(CircleShape)
                .background(colors.bg.copy(alpha = 0.55f))
                .border(1.dp, colors.lineSoft, CircleShape)
                .tvFocusGlow(backFocus, cornerRadiusDp = 20f)
                .tvClickable(backFocus, onBack),
            contentAlignment = Alignment.Center,
        ) {
            KobButton(
                onClick = onBack,
                style = KobButtonStyle.Ghost,
                accent = accent,
                modifier = Modifier.fillMaxSize(),
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                    tint = colors.textHi,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Text(
            "STREAMS",
            style = KobappTheme.type.bodySm.copy(letterSpacing = 0.12.em),
            color = colors.textLo,
        )
    }
}

@Composable
private fun StreamPickerHeader(provider: String?, instantCount: Int, totalCount: Int) {
    val colors = KobappTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Stream options", style = KobappTheme.type.h3, color = colors.textHi)
        val trailing = buildString {
            if (provider != null) append(provider)
            if (totalCount > 0) {
                if (isNotEmpty()) append(" · ")
                append("$instantCount instant")
            }
        }
        if (trailing.isNotEmpty()) {
            Text(
                trailing,
                style = KobappTheme.type.bodySm,
                color = colors.textLo,
            )
        }
    }
}

@Composable
private fun LoadingState() {
    val colors = KobappTheme.colors
    Box(
        Modifier.fillMaxWidth().heightIn(min = 160.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = colors.textHi, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(12.dp))
            Text("Searching for streams…", style = KobappTheme.type.bodySm, color = colors.textMd)
        }
    }
}

@Composable
private fun EmptyState() {
    val colors = KobappTheme.colors
    Box(
        Modifier.fillMaxWidth().heightIn(min = 160.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Outlined.CloudOff,
                contentDescription = null,
                tint = colors.textLo,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text("No streams found", style = KobappTheme.type.bodySm, color = colors.textMd)
        }
    }
}

@Composable
private fun StreamList(
    options: List<StreamOption>,
    recentStreams: List<RecentStreamDto>,
    onSelect: (StreamOption) -> Unit,
    onPlayRecent: ((RecentStreamDto) -> Unit)?,
    api: KobblerApi?,
    accent: Color,
    firstStreamFocus: FocusRequester,
) {
    val colors = KobappTheme.colors
    val scope = rememberCoroutineScope()
    val playRecent = onPlayRecent.takeIf { recentStreams.isNotEmpty() }

    LazyColumn(
        contentPadding = PaddingValues(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (playRecent != null) {
            item {
                SectionLabel("Recent")
                Spacer(Modifier.height(4.dp))
            }
            itemsIndexed(recentStreams, key = { _, item -> "recent-${item.id}" }) { idx, recent ->
                var pinned by remember(recent.id) { mutableStateOf(recent.pinned) }
                RecentStreamRow(
                    recent = recent,
                    pinned = pinned,
                    accent = accent,
                    entryFocus = if (idx == 0) firstStreamFocus else null,
                    onPlay = { playRecent(recent) },
                    onTogglePin = {
                        if (api != null) {
                            scope.launch {
                                try {
                                    if (pinned) api.unpinStream(recent.id)
                                    else api.pinStream(recent.id)
                                    pinned = !pinned
                                } catch (_: Exception) {}
                            }
                        }
                    },
                )
            }
            item {
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.lineSoft))
                Spacer(Modifier.height(8.dp))
                SectionLabel("Available")
                Spacer(Modifier.height(4.dp))
            }
        }

        itemsIndexed(options, key = { _, item -> item.id }) { idx, option ->
            StreamRow(
                option = option,
                accent = accent,
                modifier = Modifier
                    .focusKey("option.${option.id}")
                    .then(if (playRecent == null && idx == 0) Modifier.focusRequester(firstStreamFocus) else Modifier),
                onSelect = { onSelect(option) },
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    val colors = KobappTheme.colors
    Text(
        text.uppercase(),
        style = KobappTheme.type.label.copy(letterSpacing = 0.12.em),
        color = colors.textLo,
    )
}

@Composable
private fun StreamRow(
    option: StreamOption,
    accent: Color,
    modifier: Modifier = Modifier,
    onSelect: () -> Unit,
) {
    val colors = KobappTheme.colors
    val focus = rememberTvFocusState()
    val isFocused = focus.isFocused
    val rowShape = RoundedCornerShape(10.dp)
    val bg = if (isFocused) colors.elev3 else Color.Transparent
    val borderColor =
        if (isFocused) lerp(Color.Transparent, accent, 0.25f) else Color.Transparent

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier
            .fillMaxWidth()
            .tvFocusGlow(focus, cornerRadiusDp = 10f, scale = 1.01f)
            .clip(rowShape)
            .background(bg)
            .border(1.dp, borderColor, rowShape)
            .tvClickable(focus, onSelect)
            .padding(14.dp),
    ) {
        KobChip(
            text = option.quality.resolution ?: "SD",
            style = KobChipStyle.AccentTint,
            accent = accent,
            modifier = Modifier.widthIn(min = 72.dp),
        )

        Column(Modifier.weight(1f)) {
            Text(
                summaryLine(option),
                style = KobappTheme.type.body.copy(fontWeight = FontWeight.Medium),
                color = colors.textHi,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                option.releaseTitle,
                style = KobappTheme.type.bodySm,
                color = colors.textMd,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            packFileName(option)?.let {
                Text(
                    "↳ $it",
                    style = KobappTheme.type.bodySm,
                    color = colors.textLo,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Column(horizontalAlignment = Alignment.End) {
            if (option.totalScore > 0) {
                Text(
                    "${(option.totalScore * 100).toInt()}%",
                    style = KobappTheme.type.body.copy(fontWeight = FontWeight.Medium),
                    color = colors.textHi,
                )
            }
            val sizeText = formatFileSize(option.size)
            if (sizeText.isNotEmpty()) {
                Text(sizeText, style = KobappTheme.type.bodySm, color = colors.textMd)
            }
        }

        // Availability is three-valued — verified cached, unknown, known uncached —
        // and only the first is worth a badge. "WILL CACHE" was drawn on every option
        // that was not confirmed instant, which is the opposite of the truth for any
        // option kobload simply had not probed.
        val history = when (option.playback) {
            PLAYBACK_WORKED -> "PLAYED BEFORE" to accent
            PLAYBACK_FAILED -> "FAILED BEFORE" to colors.err
            else -> null
        }
        if (option.cached || history != null) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (option.cached) StreamPill("INSTANT", colors.ok)
                history?.let { (label, tint) -> StreamPill(label, tint) }
            }
        }
    }
}

@Composable
private fun StreamPill(label: String, acc: Color) {
    val colors = KobappTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clip(KobappTheme.shapes.pill)
            .background(lerp(colors.elev1, acc, 0.14f))
            .border(1.dp, lerp(Color.Transparent, acc, 0.30f), KobappTheme.shapes.pill)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(acc))
        Text(
            label,
            style = KobappTheme.type.labelSm.copy(letterSpacing = 0.1.em),
            color = acc,
        )
    }
}

@Composable
private fun RecentStreamRow(
    recent: RecentStreamDto,
    pinned: Boolean,
    accent: Color,
    entryFocus: FocusRequester?,
    modifier: Modifier = Modifier,
    onPlay: () -> Unit,
    onTogglePin: () -> Unit,
) {
    val colors = KobappTheme.colors
    val focus = rememberTvFocusState()
    val isFocused = focus.isFocused
    val rowShape = RoundedCornerShape(10.dp)
    val bg = if (isFocused) colors.elev3 else Color.Transparent
    val borderColor =
        if (isFocused) lerp(Color.Transparent, accent, 0.25f) else Color.Transparent

    // The pin is a sibling of the row body, not a child of it: a focusable ancestor
    // swallows a D-pad press aimed at its own descendants, so nesting it left it
    // reachable only with a mouse. See EpisodeRowFocusTest.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier
            .fillMaxWidth()
            .focusKeyGroup("recent.${recent.id}")
            .clip(rowShape)
            .background(bg)
            .border(1.dp, borderColor, rowShape)
            .focusGroup()
            .padding(14.dp),
    ) {
        Column(
            Modifier
                .weight(1f)
                .focusKey("row")
                .then(if (entryFocus != null) Modifier.focusRequester(entryFocus) else Modifier)
                .tvFocusGlow(focus, cornerRadiusDp = 10f, scale = 1.01f)
                .clip(rowShape)
                .tvClickable(focus, onPlay),
        ) {
            Text(
                recent.mediaTitle ?: recent.releaseTitle,
                style = KobappTheme.type.body.copy(fontWeight = FontWeight.Medium),
                color = colors.textHi,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            val meta = buildString {
                append(recent.debridProvider)
                val size = formatFileSize(recent.filesize)
                if (size.isNotEmpty()) append(" · ").append(size)
            }
            Text(
                meta,
                style = KobappTheme.type.bodySm,
                color = colors.textLo,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        val pinFocus = rememberTvFocusState()
        Box(
            modifier = Modifier
                .focusKey("pin")
                .size(32.dp)
                .clip(CircleShape)
                .tvFocusGlow(pinFocus, cornerRadiusDp = 16f)
                .tvClickable(pinFocus, onTogglePin),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.PushPin,
                contentDescription = if (pinned) "Unpin" else "Pin",
                tint = if (pinned) accent else colors.textXlo,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private fun summaryLine(option: StreamOption): String = buildList {
    add(sourceName(option))
    option.quality.codec?.let { add(it) }
    option.quality.hdr?.let { add(it) }
    option.quality.audio?.let { add(it) }
}.joinToString(" · ")

private fun sourceName(option: StreamOption): String {
    val indexer = sourceLabel(option.indexer) ?: option.indexer.takeIf { it.isNotBlank() }
    return when {
        option.sourceType == "usenet" -> listOfNotNull("Usenet", indexer).joinToString(" · ")
        indexer != null -> indexer
        else -> "Torrent"
    }
}

// A season pack's release name says nothing about which episode plays; the file does.
private fun packFileName(option: StreamOption): String? =
    option.fileName?.takeUnless { option.releaseTitle.startsWith(it.substringBeforeLast('.')) }

private fun sourceLabel(indexer: String): String? {
    if (!indexer.startsWith("hash_index:")) return null
    return when (val source = indexer.removePrefix("hash_index:")) {
        "rarbg_dump" -> "RARBG"
        "tpb" -> "TPB"
        "dmm" -> "DMM"
        "stremthru" -> "StremThru"
        "torrentio" -> "Torrentio"
        "eztv" -> "EZTV"
        "dht" -> "DHT"
        "rss" -> "RSS"
        "live_search" -> "Live"
        else -> source
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) {
        val rounded = (gb * 10).toLong() / 10.0
        "$rounded GB"
    } else {
        val mb = (bytes / (1024.0 * 1024.0)).toLong()
        "$mb MB"
    }
}
