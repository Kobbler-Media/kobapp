package dev.kobbler.app.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.tvAutoGlow
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup
import dev.kobbler.app.ui.safeBottomPadding

@Composable
internal fun ReaderBottomBar(
    pages: () -> Pair<Int, Int>,
    currentChapter: Int,
    totalChapters: Int,
    onSliderChange: (Float) -> Unit,
    theme: ReaderTheme,
    entryFocus: FocusRequester,
) {
    var showSlider by remember { mutableStateOf(false) }
    Column(
        Modifier
            .focusKeyGroup("bottombar")
            .fillMaxWidth()
            .background(theme.backgroundColor)
            .padding(horizontal = 16.dp)
            .safeBottomPadding()
    ) {
        if (showSlider && totalChapters > 1) {
            var dragged by remember { mutableStateOf<Float?>(null) }
            Slider(
                value = dragged ?: (currentChapter.toFloat() / (totalChapters - 1).coerceAtLeast(1)),
                onValueChange = { dragged = it },
                onValueChangeFinished = {
                    dragged?.let(onSliderChange)
                    dragged = null
                },
                modifier = Modifier.height(28.dp),
                colors = SliderDefaults.colors(
                    thumbColor = theme.linkColor,
                    activeTrackColor = theme.linkColor,
                ),
            )
        }
        Box(
            Modifier
                .focusKey("progress", focusRequester = entryFocus)
                .fillMaxWidth()
                .tvAutoGlow(cornerRadiusDp = 6f, scale = 1.01f)
                .clickable { showSlider = !showSlider }
                .padding(vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                readerProgressLabel(pages(), currentChapter, totalChapters),
                style = KobappTheme.type.caption.copy(color = theme.textColor.copy(alpha = 0.5f)),
            )
        }
    }
}
