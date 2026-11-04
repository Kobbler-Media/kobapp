package dev.kobbler.app.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.StreamOption
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.theme.LocalFormFactor
import dev.kobbler.app.ui.components.KobChip
import dev.kobbler.app.ui.components.KobChipStyle
import dev.kobbler.app.ui.components.TvFocusScale
import dev.kobbler.app.ui.components.rememberTvFocusState
import dev.kobbler.app.ui.components.tvClickable
import dev.kobbler.app.ui.components.tvFocusGlow
import dev.kobbler.app.ui.components.tvMarquee
import dev.kobbler.app.ui.player.PLAYBACK_FAILED
import dev.kobbler.app.ui.player.PLAYBACK_WORKED
import dev.kobbler.app.ui.player.isInstant

@Composable
internal fun StreamOptionRow(
    option: StreamOption,
    accent: Color,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KobappTheme.colors
    val compact = LocalFormFactor.current == FormFactor.Phone
    val focus = rememberTvFocusState()
    var whole by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    val meta = streamMeta(option)

    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp),
        modifier = modifier
            .fillMaxWidth()
            .tvFocusGlow(focus, cornerRadiusDp = 10f, scale = TvFocusScale.Subtle)
            .clip(shape)
            .background(colors.elev1)
            .pointerHoverIcon(PointerIcon.Hand)
            .tvClickable(focus, onLongClick = { whole = !whole }, onClick = onSelect)
            .padding(horizontal = if (compact) 12.dp else 16.dp, vertical = 12.dp),
    ) {
        KobChip(
            text = option.quality.resolution ?: "SD",
            style = KobChipStyle.AccentTint,
            accent = accent,
            modifier = Modifier.widthIn(min = if (compact) 58.dp else 68.dp),
        )

        Column(Modifier.weight(1f)) {
            Text(
                option.releaseTitle,
                style = KobappTheme.type.body.copy(fontWeight = FontWeight.Medium),
                color = colors.textHi,
                maxLines = if (whole) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.tvMarquee(focus.isFocused && !whole),
            )
            packFileName(option)?.let {
                Text(
                    "↳ $it",
                    style = KobappTheme.type.caption,
                    color = colors.textLo,
                    maxLines = if (whole) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val chips = streamChips(option)
            if (chips.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    chips.forEach { StreamChip(it, accent) }
                }
            }
            if (compact && meta.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    meta.forEachIndexed { i, part ->
                        if (i > 0) Text("·", style = KobappTheme.type.caption, color = colors.textXlo)
                        Text(part, style = KobappTheme.type.caption, color = colors.textLo, maxLines = 1)
                    }
                }
            }
        }

        if (!compact && meta.isNotEmpty()) {
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(min = 96.dp)) {
                meta.forEachIndexed { i, part ->
                    Text(
                        part,
                        style = if (i == 0) KobappTheme.type.body.copy(fontWeight = FontWeight.Medium) else KobappTheme.type.caption,
                        color = if (i == 0) colors.textHi else colors.textLo,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private enum class ChipTone { Spec, Ok, Accent, Err }

private data class StreamChipSpec(val label: String, val tone: ChipTone)

@Composable
private fun StreamChip(chip: StreamChipSpec, accent: Color) {
    val colors = KobappTheme.colors
    val tint = when (chip.tone) {
        ChipTone.Spec -> null
        ChipTone.Ok -> colors.ok
        ChipTone.Accent -> accent
        ChipTone.Err -> colors.err
    }
    if (tint == null) {
        KobChip(chip.label, style = KobChipStyle.Ghost)
    } else {
        KobChip(
            chip.label,
            style = KobChipStyle.AccentTint,
            accent = tint,
            leading = { Box(Modifier.size(6.dp).clip(CircleShape).background(tint)) },
        )
    }
}

// Of ready / likely / unknown / uncached, only the first earns a chip: "will cache" on
// everything not confirmed instant is the opposite of the truth for an option kobload
// simply had not probed. A grouped option's history is already its section's heading.
private fun streamChips(option: StreamOption): List<StreamChipSpec> = buildList {
    if (option.isInstant) add(StreamChipSpec("Instant", ChipTone.Ok))
    if (option.group == null) when (option.playback) {
        PLAYBACK_WORKED -> add(StreamChipSpec("Played before", ChipTone.Accent))
        PLAYBACK_FAILED -> add(StreamChipSpec("Failed before", ChipTone.Err))
    }
    listOfNotNull(option.quality.hdr, option.quality.codec, option.quality.audio)
        .forEach { add(StreamChipSpec(streamSpecLabel(it), ChipTone.Spec)) }
    if (option.audioLanguages.isNotEmpty()) {
        add(StreamChipSpec(option.audioLanguages.joinToString(" · ") { it.uppercase() }, ChipTone.Spec))
    }
}

private fun streamMeta(option: StreamOption): List<String> = listOfNotNull(
    formatFileSize(option.size).ifEmpty { null },
    sourceName(option),
)

internal fun streamSpecLabel(raw: String): String = when (raw.lowercase()) {
    "avc", "h264", "h.264" -> "H.264"
    "hevc", "h265", "h.265" -> "HEVC"
    "av1" -> "AV1"
    "ddp", "eac3", "dd+" -> "DD+"
    "ac3", "dd" -> "DD"
    "truehd" -> "TrueHD"
    "dts" -> "DTS"
    "aac" -> "AAC"
    "flac" -> "FLAC"
    else -> raw
}

internal fun debridServiceName(raw: String): String = when (raw.lowercase()) {
    "real_debrid", "realdebrid" -> "Real-Debrid"
    "all_debrid", "alldebrid" -> "AllDebrid"
    "debrid_link", "debridlink" -> "Debrid-Link"
    "premiumize" -> "Premiumize"
    "torbox" -> "TorBox"
    else -> raw.split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
}

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
