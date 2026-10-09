package dev.kobbler.app.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.BrightnessHigh
import androidx.compose.material.icons.outlined.BrightnessLow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.TtsVoiceDto
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.KobChip
import dev.kobbler.app.ui.components.KobChipStyle
import dev.kobbler.app.ui.components.tvAutoGlow
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup
import dev.kobbler.app.ui.safeBottomPadding

@Composable
internal fun ReaderSettingsPanel(
    themeMode: ReaderThemeMode,
    fontSize: Float,
    lineHeightMultiplier: Float,
    horizontalPadding: Int,
    readerFontFamily: ReaderFontFamily,
    onThemeChange: (ReaderThemeMode) -> Unit,
    onFontSizeChange: (Float) -> Unit,
    onLineHeightChange: (Float) -> Unit,
    onPaddingChange: (Int) -> Unit,
    onFontFamilyChange: (ReaderFontFamily) -> Unit,
    brightness: Float,
    onBrightnessChange: (Float) -> Unit,
    ttsController: TtsController,
    ttsVoices: List<TtsVoiceDto>,
    onDismiss: () -> Unit,
    theme: ReaderTheme,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(theme.backgroundColor.copy(alpha = 0.9f))
            .clickable(onClick = onDismiss)
    ) {
        Column(
            Modifier
                .focusKeyGroup("settings")
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(theme.backgroundColor, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .clickable(enabled = false) {}
                .padding(24.dp)
                .safeBottomPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Reader Settings", style = KobappTheme.type.h3.copy(color = theme.textColor))
            Spacer(Modifier.height(20.dp))

            SettingsLabel("Font Size: ${fontSize.toInt()}sp", theme)
            ThemedSlider(
                value = fontSize,
                onValueChange = onFontSizeChange,
                valueRange = 12f..28f,
                steps = 7,
                theme = theme,
            )
            Spacer(Modifier.height(12.dp))

            SettingsLabel("Font", theme)
            Spacer(Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                for (family in ReaderFontFamily.entries) {
                    val isSelected = family == readerFontFamily
                    KobChip(
                        text = family.label,
                        style = if (isSelected) KobChipStyle.AccentTint else KobChipStyle.Default,
                        accent = theme.linkColor,
                        onClick = { onFontFamilyChange(family) },
                        modifier = Modifier.focusKey("font.${family.name}").weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            SettingsLabel("Line Spacing: ${((lineHeightMultiplier * 10).toInt() / 10.0)}x", theme)
            ThemedSlider(
                value = lineHeightMultiplier,
                onValueChange = onLineHeightChange,
                valueRange = 1.2f..2.2f,
                steps = 9,
                theme = theme,
            )
            Spacer(Modifier.height(12.dp))

            SettingsLabel("Margins: ${horizontalPadding}dp", theme)
            ThemedSlider(
                value = horizontalPadding.toFloat(),
                onValueChange = { onPaddingChange(it.toInt()) },
                valueRange = 8f..40f,
                steps = 7,
                theme = theme,
            )
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.BrightnessLow, null, tint = theme.textColor.copy(alpha = 0.5f), modifier = Modifier.size(16.dp))
                Slider(
                    value = brightness,
                    onValueChange = onBrightnessChange,
                    valueRange = 0.15f..1f,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    colors = SliderDefaults.colors(thumbColor = theme.linkColor, activeTrackColor = theme.linkColor),
                )
                Icon(Icons.Outlined.BrightnessHigh, null, tint = theme.textColor.copy(alpha = 0.5f), modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.height(16.dp))

            SettingsLabel("Theme", theme)
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                for (mode in ReaderThemeMode.entries) {
                    val modeTheme = mode.toTheme()
                    val isSelected = mode == themeMode
                    Box(
                        Modifier
                            .focusKey("theme.${mode.name}")
                            .weight(1f)
                            .tvAutoGlow(cornerRadiusDp = 999f, scale = 1.02f)
                            .clip(RoundedCornerShape(50))
                            .background(modeTheme.backgroundColor)
                            .then(
                                if (isSelected) Modifier.border(2.dp, theme.linkColor, RoundedCornerShape(50))
                                else Modifier
                            )
                            .clickable { onThemeChange(mode) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(mode.name, style = KobappTheme.type.bodySm.copy(color = modeTheme.textColor))
                    }
                }
            }

            if (ttsVoices.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                HorizontalDivider(color = theme.textColor.copy(alpha = 0.15f))
                Spacer(Modifier.height(16.dp))
                Text("TTS Settings", style = KobappTheme.type.h3.copy(color = theme.textColor))
                Spacer(Modifier.height(12.dp))

                SettingsLabel("Voice", theme)
                Spacer(Modifier.height(4.dp))
                var voiceExpanded by remember { mutableStateOf(false) }
                val selectedVoiceName = ttsVoices.find { it.id == ttsController.selectedVoice }?.name ?: "Default"
                Box(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .focusKey("voice")
                            .fillMaxWidth()
                            .tvAutoGlow(cornerRadiusDp = 10f, scale = 1.01f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(KobappTheme.colors.elev2)
                            .border(1.dp, KobappTheme.colors.line, RoundedCornerShape(10.dp))
                            .clickable { voiceExpanded = true }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            selectedVoiceName,
                            style = KobappTheme.type.body.copy(color = theme.textColor),
                            modifier = Modifier.weight(1f),
                        )
                        Icon(Icons.Outlined.ArrowDropDown, null, tint = theme.textColor, modifier = Modifier.size(20.dp))
                    }
                    DropdownMenu(
                        expanded = voiceExpanded,
                        onDismissRequest = { voiceExpanded = false },
                    ) {
                        // Naming nothing is a real choice, and the better one: the server
                        // resolves the active preset and pins a narrator that holds across
                        // chapters. Without a way back to it a single tap here is permanent.
                        DropdownMenuItem(
                            text = { Text("Default") },
                            onClick = {
                                ttsController.selectedVoice = null
                                voiceExpanded = false
                            },
                        )
                        for (voice in ttsVoices) {
                            DropdownMenuItem(
                                text = { Text(voice.name) },
                                onClick = {
                                    ttsController.selectedVoice = voice.id
                                    voiceExpanded = false
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                val ttsSpeed by ttsController.selectedSpeed.collectAsState()
                SettingsLabel(
                    "Speed: ${((ttsSpeed * 10).toInt() / 10.0)}x (applies to the next chapter)",
                    theme,
                )
                ThemedSlider(
                    value = ttsSpeed.toFloat(),
                    onValueChange = { ttsController.setSpeed(it.toDouble()) },
                    valueRange = 0.5f..2.5f,
                    steps = 7,
                    theme = theme,
                )
            }
        }
    }
}

@Composable
private fun SettingsLabel(text: String, theme: ReaderTheme) {
    Text(text, style = KobappTheme.type.caption.copy(color = theme.textColor))
}

@Composable
private fun ThemedSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    theme: ReaderTheme,
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        steps = steps,
        colors = SliderDefaults.colors(thumbColor = theme.linkColor, activeTrackColor = theme.linkColor),
    )
}
