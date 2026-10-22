package dev.kobbler.app.ui.detail

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
import dev.kobbler.kobfocus.focusKey

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
    refusals: List<Pair<String, () -> Unit>> = emptyList(),
) {
    val colors = KobappTheme.colors
    val wantable = showWant && detail.libraryStatus == LibraryStatus.NotInLibrary
    val wantLabel = if (wantLoading) "Adding…" else "Want"
    DetailActionRow(
        wide = wide,
        modifier = modifier,
        exitDown = exitDown,
        primary = when {
            stream != null -> { fill ->
                PrimaryAction(
                    icon = Icons.Outlined.PlayArrow,
                    label = stream.label,
                    detail = stream.detail,
                    onClick = stream.play,
                    modifier = Modifier.focusKey("play", preferredEntry = true).then(fill),
                )
            }
            wantable -> { fill ->
                PrimaryAction(
                    icon = Icons.Outlined.BookmarkAdd,
                    label = wantLabel,
                    onClick = onWant,
                    enabled = !wantLoading,
                    modifier = Modifier.focusKey("want", preferredEntry = true).then(fill),
                )
            }
            else -> null
        },
    ) {
        if (stream != null) {
            IconAction(
                icon = Icons.Outlined.Tune,
                label = "Stream options",
                accent = accent,
                onClick = stream.pick,
                modifier = Modifier.focusKey("streams"),
            )
            if (wantable) {
                IconAction(
                    icon = Icons.Outlined.BookmarkAdd,
                    label = wantLabel,
                    accent = accent,
                    onClick = onWant,
                    enabled = !wantLoading,
                    modifier = Modifier.focusKey("want"),
                )
            }
        }
        refusals.forEachIndexed { i, (label, refuse) ->
            IconAction(
                icon = if (i == 0) Icons.Outlined.ThumbDown else Icons.Outlined.HistoryToggleOff,
                label = label,
                accent = accent,
                onClick = refuse,
                modifier = Modifier.focusKey("refuse.$i"),
            )
        }
        if (showWant && detail.libraryStatus == LibraryStatus.Wanted) {
            IconAction(
                icon = Icons.Outlined.Check,
                label = "Wanted",
                tint = colors.warn,
                accent = accent,
                onClick = {},
                modifier = Modifier.focusKey("wanted"),
            )
        }
    }
}
