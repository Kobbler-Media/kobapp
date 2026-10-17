package dev.kobbler.app.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.ChoiceRow
import dev.kobbler.app.ui.components.KobButton
import dev.kobbler.app.ui.components.KobButtonStyle
import dev.kobbler.kobfocus.focusKey

/**
 * How much of a show to keep asking for. The modes are the server's own
 * (`VALID_SHOW_AUTO_WANT_MODES`); picking any of them applies to existing seasons
 * immediately, not just to episodes that have yet to air.
 */
@Composable
internal fun WantShowDialog(
    currentMode: String,
    monitored: Boolean,
    accent: Color,
    onDismiss: () -> Unit,
    onPickMode: (String) -> Unit,
    /**
     * One pass over what has already aired, following nothing. Null where there is
     * nothing to be missing from: a show that is not in the library yet has no episodes
     * for this to act on — creating it only enqueues the fetch that discovers them — and
     * a one-shot want is the one intent nothing persists to re-try when they arrive.
     */
    onWantMissingOnce: (() -> Unit)?,
) {
    val colors = KobappTheme.colors
    val options = listOf(
        Triple("all", "All episodes", "Every episode without a file, now and as they air"),
        Triple("new_only", "New episodes", "Only episodes around their air date"),
        Triple("fill_gaps", "Gaps only", "Missing episodes between the ones you have"),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Want this show", color = colors.textHi, style = KobappTheme.type.h3) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                options.forEach { (mode, label, detail) ->
                    ChoiceRow(
                        label = label,
                        detail = detail,
                        selected = monitored && currentMode == mode,
                        accent = accent,
                        focusKeyName = "wantmode.$mode",
                        onClick = { onPickMode(mode) },
                    )
                }
                if (onWantMissingOnce != null) {
                    HorizontalDivider(color = colors.lineSoft, modifier = Modifier.padding(vertical = 6.dp))
                    ChoiceRow(
                        label = "Just the missing ones, once",
                        detail = "Ask for what is missing now and leave monitoring alone",
                        selected = false,
                        accent = accent,
                        focusKeyName = "wantmode.once",
                        onClick = onWantMissingOnce,
                    )
                }
                if (monitored) {
                    ChoiceRow(
                        label = "Stop wanting",
                        detail = "Keep what you have and ask for nothing more",
                        selected = false,
                        accent = accent,
                        focusKeyName = "wantmode.none",
                        onClick = { onPickMode("none") },
                    )
                }
            }
        },
        confirmButton = {
            KobButton(onClick = onDismiss, style = KobButtonStyle.Ghost, modifier = Modifier.focusKey("wantmode.cancel")) {
                Text("Cancel", color = colors.textHi, style = KobappTheme.type.body)
            }
        },
        containerColor = colors.elev1,
        titleContentColor = colors.textHi,
        textContentColor = colors.textMd,
    )
}

internal fun wantModeLabel(mode: String): String = when (mode) {
    "all" -> "All episodes"
    "new_only" -> "New episodes"
    "fill_gaps" -> "Gaps only"
    else -> "Nothing"
}
