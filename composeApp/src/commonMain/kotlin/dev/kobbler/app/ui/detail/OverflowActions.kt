package dev.kobbler.app.ui.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.MoreHoriz
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import androidx.compose.material.icons.outlined.PlaylistAddCheck
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.KobButtonStyle
import dev.kobbler.app.ui.player.KeepAction
import dev.kobbler.kobfocus.focusKey

/**
 * The "everything else" control at the end of an action row.
 *
 * [content] receives a close callback; an item that starts work is responsible for
 * dismissing the menu itself, so a slow request does not hold it open.
 */
@Composable
internal fun OverflowActions(
    accent: Color,
    modifier: Modifier = Modifier,
    label: String = "More",
    icon: ImageVector = Icons.Outlined.MoreHoriz,
    focusKeyName: String = "action.more",
    quiet: Boolean = false,
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val anchor = remember { FocusRequester() }
    Box(modifier) {
        IconAction(
            icon = icon,
            label = label,
            onClick = { open = true },
            accent = accent,
            modifier = Modifier.focusKey(focusKeyName).focusRequester(anchor),
            style = if (quiet) KobButtonStyle.Ghost else KobButtonStyle.Default,
            captioned = !quiet,
        )
        OverflowMenu(expanded = open, anchor = anchor, onClose = { open = false }, content = content)
    }
}

/**
 * The dropdown itself, for anything that opens one: the overflow button, or a control that
 * opens a menu on OK. Closing hands focus back to [anchor].
 */
@Composable
internal fun OverflowMenu(
    expanded: Boolean,
    anchor: FocusRequester,
    onClose: () -> Unit,
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    val colors = KobappTheme.colors
    // The menu is its own window: it swallows the remote's keys, but nothing in it holds
    // focus until asked. The first item composed claims the requester, so the item list
    // stays free to be conditional and the menu opens on its top entry.
    val entry = remember(expanded) { OverflowEntry() }
    LaunchedEffect(expanded) {
        if (!expanded) return@LaunchedEffect
        repeat(10) {
            if (runCatching { entry.requester.requestFocus() }.isSuccess) return@LaunchedEffect
            withFrameNanos { }
        }
    }
    val close = {
        onClose()
        runCatching { anchor.requestFocus() }
        Unit
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = close,
        shape = KobappTheme.shapes.small,
        containerColor = colors.elev2,
        // Material's own surface treatment shows through as a pale outline behind the
        // panel on this theme; the app draws its own edges.
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, colors.line),
    ) {
        CompositionLocalProvider(LocalOverflowEntry provides entry) {
            content(close)
        }
    }
}

private class OverflowEntry {
    val requester = FocusRequester()
    private var claimed = false
    fun claim(): Boolean = !claimed.also { claimed = true }
}

private val LocalOverflowEntry = compositionLocalOf<OverflowEntry?> { null }

@Composable
internal fun OverflowItem(
    key: String,
    icon: ImageVector,
    label: String,
    accent: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val colors = KobappTheme.colors
    // A filled row rather than the ring the rest of the app uses: the ring is drawn
    // outside the element's bounds and a menu row has none to spare — it escaped the
    // panel as a stray white outline.
    var focused by remember { mutableStateOf(false) }
    val entry = LocalOverflowEntry.current
    val isEntry = remember { entry?.claim() == true }
    DropdownMenuItem(
        text = {
            Text(
                label,
                style = KobappTheme.type.body,
                color = if (enabled) colors.textHi else accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingIcon = {
            Icon(icon, null, tint = if (enabled) colors.textMd else accent, modifier = Modifier.size(18.dp))
        },
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .focusKey("action.more.$key")
            .then(if (isEntry && entry != null) Modifier.focusRequester(entry.requester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .background(if (focused) accent.copy(alpha = 0.18f) else Color.Transparent),
    )
}

/**
 * Optimistic: the server round trip is not worth a spinner on a control whose entire
 * value is feeling instant. A failure reverts and says so. [scope] belongs to the page,
 * not the menu: the menu is disposed as it closes, and the request with it.
 */
@Composable
internal fun WatchlistItem(
    inList: Boolean,
    accent: Color,
    toast: ToastState,
    scope: CoroutineScope,
    onChanged: (Boolean) -> Unit,
    toggle: suspend (Boolean) -> Unit,
    close: () -> Unit,
) {
    // NOT the eye: `Visibility` is the watched toggle, and one glyph meaning two things is
    // worse than a duller one.
    OverflowItem(
        key = "watchlist",
        icon = if (inList) Icons.Outlined.PlaylistAddCheck else Icons.Outlined.PlaylistAdd,
        label = if (inList) "Remove from your list" else "Add to your list",
        accent = accent,
        onClick = {
            val next = !inList
            onChanged(next)
            close()
            scope.launch {
                runCatching { toggle(next) }
                    .onSuccess { toast.show(if (next) "Added to your list" else "Removed from your list") }
                    .onFailure {
                        onChanged(!next)
                        toast.show("Could not update your list")
                    }
            }
        },
    )
}

@Composable
internal fun KeepItem(keep: KeepAction, accent: Color, close: () -> Unit) {
    OverflowItem(
        key = "keep",
        icon = Icons.Outlined.LibraryAdd,
        label = keep.label,
        accent = accent,
        enabled = keep.enabled,
        onClick = {
            close()
            keep.run()
        },
    )
}
