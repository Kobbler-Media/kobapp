package dev.kobbler.app.ui.player.audiobook

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.tvAutoGlow
import dev.kobbler.kobfocus.focusKey

@Composable
fun SleepTimerMenu(
    mode: SleepMode,
    remainingMs: () -> Long,
    onSelect: (SleepMode) -> Unit,
) {
    val colors = KobappTheme.colors
    val type = KobappTheme.type
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.focusKey("sleep").tvAutoGlow(cornerRadiusDp = 999f, scale = 1.08f),
        ) {
            if (mode != SleepMode.Off) {
                val ms = remainingMs()
                val label = when {
                    mode == SleepMode.EndOfChapter -> "EoC"
                    ms < 5 * 60 * 1000L -> {
                        val mins = ms / 60000
                        val secs = (ms % 60000) / 1000
                        "$mins:${secs.toString().padStart(2, '0')}"
                    }
                    else -> "${ms / 60000}m"
                }
                Text(label, color = colors.accAudio, style = type.bodySm, fontWeight = FontWeight.Bold)
            } else {
                Icon(Icons.Outlined.Bedtime, "Sleep timer", tint = colors.textMd)
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = colors.elev2,
        ) {
            SleepMode.entries.forEach { entry ->
                val label = when (entry) {
                    SleepMode.Off -> "Off"
                    SleepMode.Min15 -> "15 minutes"
                    SleepMode.Min30 -> "30 minutes"
                    SleepMode.Min45 -> "45 minutes"
                    SleepMode.Min60 -> "60 minutes"
                    SleepMode.EndOfChapter -> "End of chapter"
                }
                val active = entry == mode
                DropdownMenuItem(
                    text = {
                        Text(
                            label,
                            color = if (active) colors.accAudio else colors.textHi,
                            style = type.body,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    onClick = {
                        onSelect(entry)
                        expanded = false
                    },
                )
            }
        }
    }
}
