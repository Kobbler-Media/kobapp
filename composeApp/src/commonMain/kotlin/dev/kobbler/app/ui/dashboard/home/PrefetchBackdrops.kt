package dev.kobbler.app.ui.dashboard.home

import androidx.compose.runtime.Composable
import dev.kobbler.app.data.UpNextItem
import dev.kobbler.app.ui.components.PrefetchToDisk

/** Up Next is where focus lands, so its backdrops are the next ones asked for. */
@Composable
fun PrefetchBackdrops(items: List<UpNextItem>) {
    PrefetchToDisk(items.drop(1).mapNotNull { it.backdropUrl })
}
