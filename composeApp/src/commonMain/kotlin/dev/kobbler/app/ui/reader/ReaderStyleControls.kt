package dev.kobbler.app.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.reader.layout.BookStyle
import dev.kobbler.app.ui.reader.layout.StyleAspect
import dev.kobbler.app.ui.reader.layout.StylePreset

private enum class StyleSource(val label: String) { Book("Book"), Mine("Mine") }

@Composable
internal fun BookStyleControls(style: BookStyle, onChange: (BookStyle) -> Unit, theme: ReaderTheme) {
    var expanded by rememberSaveable { mutableStateOf(style.preset == StylePreset.Custom) }
    Column(Modifier.fillMaxWidth()) {
        ChoiceChips(StylePreset.entries, style.preset, { it.label }, { preset ->
            when (preset) {
                StylePreset.Book -> onChange(BookStyle.Book)
                StylePreset.Mine -> onChange(BookStyle.Mine)
                StylePreset.Custom -> expanded = !expanded
            }
        }, theme, key = "style")
        if (expanded) for (aspect in StyleAspect.entries) {
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = aspect.label },
            ) {
                Text(
                    aspect.label,
                    style = KobappTheme.type.caption.copy(color = theme.textColor),
                    modifier = Modifier.weight(1f),
                )
                Box(Modifier.width(ChipsWidth)) {
                    ChoiceChips(
                        StyleSource.entries,
                        if (style.uses(aspect)) StyleSource.Book else StyleSource.Mine,
                        { it.label },
                        { onChange(style.with(aspect, book = it == StyleSource.Book)) },
                        theme,
                        key = "style.${aspect.key}",
                    )
                }
            }
        }
    }
}

private val ChipsWidth = 160.dp
