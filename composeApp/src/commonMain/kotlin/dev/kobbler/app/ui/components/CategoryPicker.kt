package dev.kobbler.app.ui.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.GenreCount
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.kobfocus.focusKey

private const val AllCategories = "All categories"

/** Shows the category in force, so the page says what it is narrowed to without opening anything. */
@Composable
fun CategoriesButton(selected: String?, accent: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    KobButton(
        text = selected ?: "Categories",
        onClick = onClick,
        style = if (selected != null) KobButtonStyle.Accent else KobButtonStyle.Ghost,
        accent = accent,
        modifier = modifier.focusKey("categories"),
        leading = { Icon(Icons.Outlined.FilterList, contentDescription = null, modifier = Modifier.size(16.dp)) },
    )
}

@Composable
fun CategoryDialog(
    genres: List<GenreCount>,
    selected: String?,
    accent: Color,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
) {
    val colors = KobappTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Categories", color = colors.textHi, style = KobappTheme.type.h3) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item(key = "all") {
                    ChoiceRow(
                        label = AllCategories,
                        detail = null,
                        selected = selected == null,
                        accent = accent,
                        focusKeyName = "category.all",
                        preferredEntry = selected == null,
                        onClick = { onPick(null) },
                    )
                }
                items(genres, key = { it.name }) { genre ->
                    ChoiceRow(
                        label = genre.name,
                        detail = if (genre.count == 1) "1 title" else "${genre.count} titles",
                        selected = genre.name == selected,
                        accent = accent,
                        focusKeyName = "category.${genre.name}",
                        preferredEntry = genre.name == selected,
                        onClick = { onPick(genre.name) },
                    )
                }
            }
        },
        confirmButton = {
            KobButton(onClick = onDismiss, style = KobButtonStyle.Ghost, modifier = Modifier.focusKey("category.cancel")) {
                Text("Cancel", color = colors.textHi, style = KobappTheme.type.body)
            }
        },
        containerColor = colors.elev1,
        titleContentColor = colors.textHi,
        textContentColor = colors.textMd,
    )
}
