package dev.kobbler.app.ui.detail

import dev.kobbler.app.ui.components.CardAction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.HistoryToggleOff
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import dev.kobbler.app.data.DiscoverDetail
import dev.kobbler.app.data.LibraryStatus
import dev.kobbler.app.theme.KobappTheme

internal class DiscoverStreamAction(val label: String, val detail: String?, val play: () -> Unit, val pick: () -> Unit)

@Composable
internal fun DiscoverActions(
    detail: DiscoverDetail,
    accent: Color,
    stream: DiscoverStreamAction?,
    wantLoading: Boolean,
    showWant: Boolean,
    onWant: () -> Unit,
    wide: Boolean,
    modifier: Modifier = Modifier,
    exitDown: FocusRequester? = null,
    refusals: List<CardAction> = emptyList(),
) {
    val colors = KobappTheme.colors
    val wantable = showWant && detail.libraryStatus == LibraryStatus.NotInLibrary
    val wantLabel = if (wantLoading) "Adding…" else "Want"
    val actions = buildList {
        if (stream != null) {
            add(RowAction("play", stream.label, Icons.Outlined.PlayArrow, stream.play, detail = stream.detail))
            add(RowAction("streams", "Stream options", Icons.Outlined.Tune, stream.pick))
        }
        if (wantable) add(RowAction("want", wantLabel, Icons.Outlined.BookmarkAdd, onWant, enabled = !wantLoading))
        refusals.forEachIndexed { i, (label, refuse) ->
            add(RowAction("refuse.$i", label, if (i == 0) Icons.Outlined.ThumbDown else Icons.Outlined.HistoryToggleOff, refuse))
        }
        if (showWant && detail.libraryStatus == LibraryStatus.Wanted) {
            add(RowAction("wanted", "Wanted", Icons.Outlined.Check, {}, tint = colors.warn))
        }
    }
    DetailActionRow(actions = actions, accent = accent, wide = wide, modifier = modifier, exitDown = exitDown)
}
