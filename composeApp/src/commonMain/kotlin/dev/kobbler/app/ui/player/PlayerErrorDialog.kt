package dev.kobbler.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.kobbler.app.data.StreamOption
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.detail.StreamOptionRow
import dev.kobbler.app.ui.components.KobButton
import dev.kobbler.app.ui.components.KobButtonStyle
import dev.kobbler.kobfocus.focusKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import dev.kobbler.kobfocus.focusKeyGroup
import dev.kobbler.app.ui.kobChromeInsets

@Composable
fun PlayerErrorDialog(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Stream failed",
    summary: String? = null,
    want: WantAction? = null,
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
            var showDetails by remember(message) { mutableStateOf(summary == null) }
            var showAlternatives by remember(alternatives) { mutableStateOf(false) }
            // The trace and the release list scroll; the buttons below never leave the screen.
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
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
                if (summary != null) {
                    Text(summary, style = KobappTheme.type.body, color = colors.textMd, textAlign = TextAlign.Center)
                }
                if (showDetails) {
                    if (summary != null) Spacer(Modifier.height(12.dp))
                    DiagnosticText(message)
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

                if (showAlternatives) {
                    Spacer(Modifier.height(20.dp))
                    AlternativesSection(
                        alternatives = alternatives,
                        accent = accent,
                        onPick = onPickAlternative,
                    )
                }
            }

            Spacer(Modifier.height(22.dp))
            // Wraps rather than squeezes: a phone is narrower than the five buttons a
            // debrid failure can offer.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (summary != null) {
                    KobButton(
                        onClick = { showDetails = !showDetails },
                        style = KobButtonStyle.Ghost,
                        modifier = Modifier.focusKey("details"),
                    ) {
                        Text(if (showDetails) "Hide details" else "Details", style = KobappTheme.type.body, color = colors.textLo)
                    }
                }
                if (alternatives.isNotEmpty() && !showAlternatives) {
                    KobButton(
                        onClick = { showAlternatives = true },
                        style = KobButtonStyle.Ghost,
                        modifier = Modifier.focusKey("choose"),
                    ) {
                        Text("Choose a release", style = KobappTheme.type.body, color = colors.textHi)
                    }
                }
                if (want != null) WantButton(want)
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
private fun DiagnosticText(message: String) {
    val colors = KobappTheme.colors
    if (message.length > 200 || message.contains('\n')) {
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
                Text(message, style = KobappTheme.type.bodySm, color = colors.textMd)
            }
        }
    } else {
        Text(message, style = KobappTheme.type.body, color = colors.textMd)
    }
}

private enum class WantState { Idle, Sending, Done, Failed }

@Composable
private fun WantButton(want: WantAction) {
    val colors = KobappTheme.colors
    val scope = rememberCoroutineScope()
    var state by remember(want) { mutableStateOf(WantState.Idle) }
    KobButton(
        onClick = {
            if (state == WantState.Idle || state == WantState.Failed) {
                state = WantState.Sending
                scope.launch {
                    state = try {
                        want.run()
                        WantState.Done
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        WantState.Failed
                    }
                }
            }
        },
        style = KobButtonStyle.Ghost,
        modifier = Modifier.focusKey("want"),
    ) {
        Text(
            when (state) {
                WantState.Idle -> "Want"
                WantState.Sending -> "Adding…"
                WantState.Done -> "Wanted"
                WantState.Failed -> "Want failed — retry"
            },
            style = KobappTheme.type.body,
            color = if (state == WantState.Done) colors.warn else colors.textHi,
        )
    }
}

@Composable
private fun AlternativesSection(
    alternatives: List<StreamOption>,
    accent: Color,
    onPick: (StreamOption) -> Unit,
) {
    val colors = KobappTheme.colors
    val cachedCount = alternatives.count { it.isInstant }
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
                StreamOptionRow(option = option, accent = accent, onSelect = { onPick(option) }, modifier = Modifier.focusKey("alt.${option.id}"))
            }
        }
    }
}
