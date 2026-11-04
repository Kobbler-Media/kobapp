package dev.kobbler.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.AudioTrackDto
import dev.kobbler.app.data.CastSkip
import dev.kobbler.app.data.CastTracks
import dev.kobbler.app.data.PlaybackSubtitleTrackDto
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.tvAutoGlow
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobplayer.TrackInfo

@Composable
internal fun CastSkipButton(skip: CastSkip, onSeekTo: (Long) -> Unit, modifier: Modifier = Modifier) {
    val colors = KobappTheme.colors
    Text(
        text = skipLabel(skip.segmentType),
        style = KobappTheme.type.bodySm,
        fontWeight = FontWeight.SemiBold,
        color = colors.textHi,
        maxLines = 1,
        modifier = modifier
            .focusKey("cast-skip")
            .tvAutoGlow(cornerRadiusDp = 999f, scale = 1.05f)
            .clip(RoundedCornerShape(999.dp))
            .border(1.dp, colors.accCal, RoundedCornerShape(999.dp))
            .clickable { onSeekTo((skip.endSeconds * 1000).toLong()) }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
internal fun CastTrackMenus(
    tracks: CastTracks,
    onSelectSubtitle: (Int?) -> Unit,
    onSelectAudio: (Int) -> Unit,
) {
    val subtitles = tracks.subtitleInfos()
    val audio = tracks.audioInfos()
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (subtitles.isNotEmpty()) {
            TrackMenu(
                key = "cast-subtitles",
                label = "Subtitles",
                options = listOf(null to "Off") + subtitles.map { it.id.toInt() to it.label },
                selected = tracks.activeSubtitleId,
                onSelect = onSelectSubtitle,
            )
        }
        if (audio.size > 1) {
            TrackMenu(
                key = "cast-audio",
                label = "Audio",
                options = audio.map { it.id.toInt() to it.label },
                selected = tracks.activeAudioIndex,
                onSelect = { it?.let(onSelectAudio) },
            )
        }
    }
}

@Composable
private fun TrackMenu(
    key: String,
    label: String,
    options: List<Pair<Int?, String>>,
    selected: Int?,
    onSelect: (Int?) -> Unit,
) {
    val colors = KobappTheme.colors
    val type = KobappTheme.type
    var expanded by remember { mutableStateOf(false) }
    val current = (options.firstOrNull { it.first == selected } ?: options.first()).second
    Box {
        Text(
            text = "$label: $current",
            style = type.bodySm,
            color = colors.textHi,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .focusKey(key)
                .tvAutoGlow(cornerRadiusDp = 999f, scale = 1.05f)
                .clip(RoundedCornerShape(999.dp))
                .background(colors.elev2)
                .clickable { expanded = true }
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, containerColor = colors.elev2) {
            options.forEach { (id, name) ->
                val active = id == selected
                DropdownMenuItem(
                    text = {
                        Text(
                            name,
                            color = if (active) colors.accCal else colors.textHi,
                            style = type.body,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    onClick = {
                        expanded = false
                        if (!active) onSelect(id)
                    },
                )
            }
        }
    }
}

private fun CastTracks.subtitleInfos(): List<TrackInfo> = subtitles.map {
    PlaybackSubtitleTrackDto(
        trackId = it.id.toString(),
        language = it.language,
        title = it.title,
        isForced = it.forced,
        isHearingImpaired = it.hearingImpaired,
    )
}.subtitleTrackInfos()

private fun CastTracks.audioInfos(): List<TrackInfo> = audioTracks.map {
    AudioTrackDto(
        streamIndex = it.streamIndex,
        codec = it.codec.orEmpty(),
        channels = it.channels ?: 0,
        language = it.language?.uppercase(),
        title = it.title,
    )
}.audioTrackInfos()
