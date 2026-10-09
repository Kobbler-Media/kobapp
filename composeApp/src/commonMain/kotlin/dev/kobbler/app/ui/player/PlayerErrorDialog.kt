package dev.kobbler.app.ui.player

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.kobbler.app.data.StreamOption
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.KobButton
import dev.kobbler.app.ui.components.KobButtonStyle
import dev.kobbler.app.ui.components.KobChip
import dev.kobbler.app.ui.components.KobChipStyle
import dev.kobbler.app.ui.components.PillStatus
import dev.kobbler.app.ui.components.StatusPill
import dev.kobbler.app.ui.components.rememberTvFocusState
import dev.kobbler.app.ui.components.tvClickable
import dev.kobbler.app.ui.components.tvFocusGlow
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup
import dev.kobbler.app.ui.kobChromeInsets

@Composable
fun PlayerErrorDialog(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Stream failed",
    alternatives: List<StreamOption> = emptyList(),
    onPickAlternative: (StreamOption) -> Unit = {},
    onRetry: (() -> Unit)? = null,
    reportStatus: ReportStatus = ReportStatus.Idle,
    onRetryReport: (() -> Unit)? = null,
) {
    val colors = KobappTheme.colors
    val accent = colors.accMovies

    Box(
        modifier
            .fillMaxSize()
            .background(colors.bg.copy(alpha = 0.78f))
            .windowInsetsPadding(kobChromeInsets),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .focusKeyGroup("playererror")
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .shadow(24.dp, RoundedCornerShape(18.dp))
                .clip(RoundedCornerShape(18.dp))
                .background(colors.elev1)
                .border(1.dp, colors.lineSoft, RoundedCornerShape(18.dp))
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = colors.err,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(14.dp))
            Text(title, style = KobappTheme.type.h3, color = colors.textHi)
            Spacer(Modifier.height(8.dp))
            val isMultiline = message.length > 200 || message.contains('\n')
            if (isMultiline) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.elev2)
                        .border(1.dp, colors.lineSoft, RoundedCornerShape(10.dp))
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                ) {
                    SelectionContainer {
                        Text(
                            message,
                            style = KobappTheme.type.bodySm,
                            color = colors.textMd,
                        )
                    }
                }
            } else {
                Text(
                    message,
                    style = KobappTheme.type.body,
                    color = colors.textMd,
                )
            }

            val reportLine = when (reportStatus) {
                ReportStatus.Idle -> null
                ReportStatus.Sending -> "Reporting to the server…"
                ReportStatus.Sent -> "Reported to the server"
                ReportStatus.Failed -> "Could not reach the server to report this"
            }
            if (reportLine != null) {
                Spacer(Modifier.height(10.dp))
                Text(reportLine, style = KobappTheme.type.bodySm, color = colors.textLo)
            }

            if (alternatives.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                AlternativesSection(
                    alternatives = alternatives,
                    accent = accent,
                    onPick = onPickAlternative,
                )
            }

            Spacer(Modifier.height(22.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Spacer(Modifier.weight(1f))
                // The report goes on its own; this only says so, and only offers a
                // button when it could not be delivered.
                if (reportStatus == ReportStatus.Failed && onRetryReport != null) {
                    KobButton(
                        onClick = onRetryReport,
                        style = KobButtonStyle.Ghost,
                        modifier = Modifier.focusKey("report"),
                    ) {
                        Text("Retry report", style = KobappTheme.type.body, color = colors.textHi)
                    }
                }
                if (onRetry != null) {
                    KobButton(onClick = onRetry, style = KobButtonStyle.Ghost, modifier = Modifier.focusKey("retry")) {
                        Text("Retry", style = KobappTheme.type.body, color = colors.textHi)
                    }
                }
                KobButton(onClick = onDismiss, style = KobButtonStyle.Ghost, modifier = Modifier.focusKey("cancel")) {
                    Text("Cancel", style = KobappTheme.type.body, color = colors.textHi)
                }
                if (alternatives.isNotEmpty()) {
                    KobButton(
                        onClick = { alternatives.firstOrNull()?.let(onPickAlternative) },
                        style = KobButtonStyle.Accent,
                        accent = accent,
                        modifier = Modifier.focusKey("tryanother"),
                    ) {
                        Text("Try another", style = KobappTheme.type.body, color = colors.bg)
                    }
                }
            }
        }
    }
}

@Composable
private fun AlternativesSection(
    alternatives: List<StreamOption>,
    accent: Color,
    onPick: (StreamOption) -> Unit,
) {
    val colors = KobappTheme.colors
    val cachedCount = alternatives.count { it.cached }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.elev2)
            .border(1.dp, colors.lineSoft, RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) {
        val label = buildString {
            append("ALTERNATIVE RELEASES")
            if (cachedCount > 0) append(" · ").append(cachedCount).append(" CACHED")
        }
        Text(
            label,
            style = KobappTheme.type.bodySm.copy(letterSpacing = 0.12.em),
            color = colors.textLo,
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            contentPadding = PaddingValues(vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.heightIn(max = 240.dp).focusKeyGroup("alternatives"),
        ) {
            items(alternatives, key = { it.id }) { option ->
                AlternativeRow(option = option, accent = accent, onPick = { onPick(option) })
            }
        }
    }
}

@Composable
private fun AlternativeRow(
    option: StreamOption,
    accent: Color,
    onPick: () -> Unit,
) {
    val colors = KobappTheme.colors
    val focus = rememberTvFocusState()
    val isFocused = focus.isFocused
    val rowShape = RoundedCornerShape(8.dp)
    val bg = if (isFocused) colors.elev3 else Color.Transparent
    val borderColor =
        if (isFocused) lerp(Color.Transparent, accent, 0.28f) else Color.Transparent

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .focusKey("alt.${option.id}")
            .fillMaxWidth()
            .tvFocusGlow(focus, cornerRadiusDp = 8f, scale = 1.01f)
            .clip(rowShape)
            .background(bg)
            .border(1.dp, borderColor, rowShape)
            .tvClickable(focus, onPick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        KobChip(
            text = option.quality.resolution ?: "SD",
            style = KobChipStyle.AccentTint,
            accent = accent,
            modifier = Modifier.widthIn(min = 64.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                altCodecLine(option),
                style = KobappTheme.type.body.copy(fontWeight = FontWeight.Medium),
                color = colors.textHi,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sizeText = altFormatSize(option.size)
            if (sizeText.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    sizeText,
                    style = KobappTheme.type.bodySm,
                    color = colors.textLo,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (option.cached) {
            StatusPill(PillStatus.InLibrary)
        } else {
            Box(
                Modifier
                    .clip(KobappTheme.shapes.pill)
                    .background(lerp(colors.elev1, colors.accDown, 0.14f))
                    .border(1.dp, lerp(Color.Transparent, colors.accDown, 0.30f), KobappTheme.shapes.pill)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(colors.accDown))
                    Text(
                        "WILL CACHE",
                        style = KobappTheme.type.labelSm.copy(letterSpacing = 0.1.em),
                        color = colors.accDown,
                    )
                }
            }
        }
    }
}

private fun altCodecLine(option: StreamOption): String {
    val parts = buildList {
        option.quality.codec?.let { add(it) }
        option.quality.hdr?.let { add(it) }
        option.quality.audio?.let { add(it) }
    }
    return if (parts.isEmpty()) option.releaseTitle else parts.joinToString(" · ")
}

private fun altFormatSize(bytes: Long): String {
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
