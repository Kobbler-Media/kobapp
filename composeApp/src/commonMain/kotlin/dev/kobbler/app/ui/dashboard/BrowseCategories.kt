package dev.kobbler.app.ui.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.kobbler.app.data.GenreCount
import dev.kobbler.app.ui.components.Async
import dev.kobbler.app.ui.components.CategoryDialog
import dev.kobbler.app.ui.components.rememberCached

/** Each category is its own cached page; the separator keeps them under one invalidation prefix. */
internal fun browseCacheKey(base: String, genre: String?): String =
    if (genre == null) base else "$base:genre:$genre"

@Composable
internal fun BrowseCategoryDialog(
    cacheKey: String,
    loadGenres: suspend () -> List<GenreCount>,
    selected: String?,
    accent: Color,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
) {
    val (state, _) = rememberCached(cacheKey) { loadGenres() }
    val genres = (state as? Async.Ready)?.value ?: emptyList()
    CategoryDialog(genres = genres, selected = selected, accent = accent, onDismiss = onDismiss, onPick = onPick)
}
