package dev.kobbler.app.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.LiveRecording
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.BackButtonGutter
import dev.kobbler.app.ui.components.KobBackButton
import dev.kobbler.app.ui.dashboard.livetv.LiveBadge
import dev.kobbler.app.ui.dashboard.livetv.LiveThumb
import dev.kobbler.app.ui.dashboard.livetv.RecordingDot
import dev.kobbler.app.ui.dashboard.livetv.hhmmRange
import dev.kobbler.app.ui.dashboard.livetv.minutesLabel
import dev.kobbler.app.ui.dashboard.livetv.sizeLabel
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup
import dev.kobbler.app.ui.LocalTopChromeOverlap
import dev.kobbler.app.ui.safeHorizontalPadding
import dev.kobbler.app.ui.UnderStatusBar
import dev.kobbler.app.ui.scrollsUnderTopChrome
import kotlinx.coroutines.launch

/**
 * A single recording. [onPlay] is the seam part 2 fills — an in-progress recording is
 * readable from its first segment, so "watch from the start" is not a completed-only
 * action.
 */
@Composable
fun LiveRecordingDetailScreen(
    recording: LiveRecording,
    api: KobblerApi,
    modifier: Modifier = Modifier,
    formFactor: FormFactor = FormFactor.Desktop,
    onBack: () -> Unit = {},
    onPlay: (LiveRecording) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val colors = KobappTheme.colors
    val dimens = KobappTheme.dimens
    val accent = colors.accLive
    val gutter = when (formFactor) {
        FormFactor.TV -> dimens.pageGutterTv
        FormFactor.Phone -> dimens.pageGutterPhone
        else -> dimens.pageGutter
    }
    var current by remember(recording.id) { mutableStateOf(recording) }
    var busy by remember { mutableStateOf(false) }

    UnderStatusBar {
        val scroll = rememberScrollState()
        Column(
            modifier
                .scrollsUnderTopChrome(scroll)
                .fillMaxSize()
                .background(colors.bg)
                .safeHorizontalPadding()
                .verticalScroll(scroll)
                .padding(top = LocalTopChromeOverlap.current)
                .padding(horizontal = gutter, vertical = dimens.s4),
            verticalArrangement = Arrangement.spacedBy(dimens.s4),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dimens.s3),
                modifier = Modifier.fillMaxWidth().focusKeyGroup("rechead"),
            ) {
                if (formFactor != FormFactor.TV) {
                    Box(Modifier.offset(x = BackButtonGutter(formFactor, gutter) - gutter)) {
                        KobBackButton(
                            onBack,
                            modifier = Modifier.focusKey("back", entryEligible = false),
                        )
                    }
                }
                LiveThumb(current.imageUrl, width = 112.dp, height = 64.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (current.isInProgress) {
                            RecordingDot(colors.err, 8.dp)
                            Text("REC", style = KobappTheme.type.labelSm, color = colors.err)
                        }
                        Text(
                            current.title,
                            style = KobappTheme.type.h1,
                            color = colors.textHi,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        LiveBadge(current.status.uppercase(), if (current.status == "failed") colors.err else accent)
                    }
                    Text(
                        listOfNotNull(
                            current.episodeNumber,
                            current.episodeTitle,
                            current.channelName,
                            hhmmRange(current.scheduledStart, current.scheduledEnd).takeIf { it.isNotEmpty() },
                        ).joinToString(" · "),
                        style = KobappTheme.type.bodySm,
                        color = colors.textMd,
                    )
                    current.provenance?.let {
                        Text(it, style = KobappTheme.type.caption, color = colors.textLo)
                    }
                }
                PrimaryAction(
                    icon = Icons.Outlined.PlayArrow,
                    label = if (current.isInProgress) "Watch from start" else "Play",
                    onClick = { onPlay(current) },
                    enabled = current.playableFromStart || current.status == "completed",
                    modifier = Modifier.focusKey("play", preferredEntry = true),
                )
                if (current.isInProgress) {
                    IconAction(
                        icon = Icons.Outlined.Stop,
                        label = "Stop recording",
                        accent = accent,
                        enabled = !busy,
                        onClick = {
                            scope.launch {
                                busy = true
                                runCatching { api.stopLiveRecording(current.id) }.onSuccess { current = it }
                                busy = false
                            }
                        },
                        modifier = Modifier.focusKey("stop", entryEligible = false),
                    )
                }
                IconAction(
                    icon = Icons.Outlined.Delete,
                    label = "Delete recording",
                    accent = accent,
                    tint = colors.err,
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            runCatching { api.deleteLiveRecording(current.id) }
                            busy = false
                            onBack()
                        }
                    },
                    modifier = Modifier.focusKey("delete", entryEligible = false),
                )
            }

            current.error?.let {
                Text(it, style = KobappTheme.type.bodySm, color = colors.err)
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(dimens.s3), verticalArrangement = Arrangement.spacedBy(dimens.s2)) {
                StatTile("Duration", minutesLabel(current.durationSeconds).ifEmpty { "—" })
                StatTile("On disk", minutesLabel(current.secondsOnDisk).ifEmpty { "—" })
                StatTile("Size", sizeLabel(current.sizeBytes).ifEmpty { "—" })
                StatTile("Tuner", current.tunerIndex?.let { "tuner$it" } ?: "—")
                StatTile("Codecs", listOfNotNull(current.videoCodec, current.audioCodec).joinToString("/").ifEmpty { "—" })
            }

            current.synopsis?.let {
                Text(it, style = KobappTheme.type.body, color = colors.textMd)
            }

            Column(verticalArrangement = Arrangement.spacedBy(dimens.s2)) {
                Text(
                    if (current.adBreaks.isEmpty()) "No ad breaks detected" else "${current.adBreaks.size} ad breaks",
                    style = KobappTheme.type.h3,
                    color = colors.textHi,
                )
                current.adBreaks.forEach { br ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(dimens.s3),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(9.dp))
                            .background(colors.elev1)
                            .border(1.dp, colors.lineSoft, RoundedCornerShape(9.dp))
                            .padding(horizontal = dimens.s3, vertical = dimens.s2),
                    ) {
                        Text(
                            "${secondsClock(br.startSeconds)} – ${secondsClock(br.endSeconds)}",
                            style = KobappTheme.type.bodySm,
                            color = colors.textHi,
                        )
                        Text(
                            listOfNotNull(
                                br.method,
                                br.confidence?.let { "${(it * 100).toInt()}% confidence" },
                                if (br.adsTotal > 0) "${br.adsKnown}/${br.adsTotal} ads known" else null,
                            ).joinToString(" · "),
                            style = KobappTheme.type.caption,
                            color = colors.textMd,
                            modifier = Modifier.weight(1f),
                        )
                        if (br.correctedAt != null) LiveBadge("CORRECTED", accent)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String) {
    val colors = KobappTheme.colors
    Column(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(lerp(colors.elev1, Color.Transparent, 0f))
            .border(1.dp, colors.lineSoft, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(label.uppercase(), style = KobappTheme.type.labelSm, color = colors.textXlo)
        Text(value, style = KobappTheme.type.bodySm.copy(fontWeight = FontWeight.SemiBold), color = colors.textHi)
    }
}

private fun secondsClock(seconds: Double): String {
    val total = seconds.toLong()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    fun p(v: Long) = if (v < 10) "0$v" else "$v"
    return if (h > 0) "$h:${p(m)}:${p(s)}" else "${p(m)}:${p(s)}"
}
