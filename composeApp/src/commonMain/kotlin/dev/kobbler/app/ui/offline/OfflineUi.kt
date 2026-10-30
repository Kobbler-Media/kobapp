package dev.kobbler.app.ui.offline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.Downloading
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import dev.kobbler.app.offline.Offline
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.kobbler.app.offline.OfflineIndex
import dev.kobbler.app.offline.OfflineItem
import dev.kobbler.app.offline.OfflineKind
import dev.kobbler.app.offline.OfflineState
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.detail.IconAction
import dev.kobbler.app.ui.detail.OverflowItem
import dev.kobbler.app.ui.detail.OverflowMenu
import dev.kobbler.kobfocus.focusKey
import kotlinx.coroutines.launch

/** The engine the UI reads; tests hand in their own so nothing touches the process-wide one. */
val LocalOfflineEngine = staticCompositionLocalOf { Offline.engine }

val offlineSupported: Boolean get() = Offline.engine != null

@Composable
fun rememberOfflineIndex(): OfflineIndex? {
    val engine = LocalOfflineEngine.current ?: return null
    val index by engine.store.index.collectAsState()
    return index
}

@Composable
fun rememberOfflineItem(kind: OfflineKind, mediaId: String?): OfflineItem? {
    val index = rememberOfflineIndex() ?: return null
    return mediaId?.let { index.items[OfflineItem.keyOf(kind, it)] }
}

@Composable
fun rememberOnline(): Boolean {
    val engine = LocalOfflineEngine.current ?: return true
    val online by engine.reachability.online.collectAsState()
    return online
}

/** The mark a title carries everywhere once it can be played with no network. */
@Composable
fun OfflineBadge(modifier: Modifier = Modifier, label: String? = "Offline") {
    val colors = KobappTheme.colors
    Row(
        modifier = modifier
            .background(colors.bg.copy(alpha = 0.78f), RoundedCornerShape(50))
            .padding(horizontal = if (label == null) 4.dp else 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Icons.Outlined.DownloadDone, contentDescription = "Available offline", tint = colors.ok, modifier = Modifier.size(14.dp))
        if (label != null) {
            Text(label, style = KobappTheme.type.caption.copy(fontWeight = FontWeight.SemiBold), color = colors.ok)
        }
    }
}

/** A detail page's meta-line entry for a download, or null when there is none. */
fun metaOffline(item: OfflineItem?): (@Composable () -> Unit)? = when (item?.state) {
    null -> null
    OfflineState.Complete -> { { OfflineBadge(label = "Available offline") } }
    else -> { { Text(stateLabel(item), style = KobappTheme.type.bodySm, color = KobappTheme.colors.textMd) } }
}

fun stateLabel(item: OfflineItem): String = when (item.state) {
    OfflineState.Queued -> "Queued"
    OfflineState.Downloading -> item.serverProgress?.let { "Preparing on server ${(it * 100).toInt()}%" }
        ?: "Downloading ${(item.progress * 100).toInt()}%"
    OfflineState.Waiting -> "Waiting for network"
    OfflineState.Complete -> "Downloaded"
    OfflineState.Failed -> "Download failed"
}

private fun iconFor(item: OfflineItem?): ImageVector = when (item?.state) {
    null -> Icons.Outlined.Download
    OfflineState.Complete -> Icons.Outlined.DownloadDone
    OfflineState.Failed -> Icons.Outlined.ErrorOutline
    else -> Icons.Outlined.Downloading
}

/**
 * The download control on a detail page: starts one, shows how it is going, and once there
 * is one, opens a menu to keep it from cleanup or remove it.
 */
@Composable
fun OfflineAction(
    item: OfflineItem?,
    accent: Color,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Download",
    focusKeyName: String = "download",
) {
    val engine = LocalOfflineEngine.current ?: return
    val colors = KobappTheme.colors
    var open by remember { mutableStateOf(false) }
    val anchor = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    Box(modifier) {
        IconAction(
            icon = iconFor(item),
            label = item?.let(::stateLabel)?.takeIf { item.state != OfflineState.Complete } ?: if (item != null) "Downloaded" else label,
            tint = when (item?.state) {
                OfflineState.Complete -> colors.ok
                OfflineState.Failed -> colors.warn
                else -> colors.textHi
            },
            accent = accent,
            onClick = { if (item == null) onDownload() else open = true },
            modifier = Modifier.focusKey(focusKeyName).focusRequester(anchor),
        )
        if (item != null) OverflowMenu(expanded = open, anchor = anchor, onClose = { open = false }) { close ->
            if (item.state == OfflineState.Failed) {
                OverflowItem("retry", Icons.Outlined.Refresh, "Try again", accent, onClick = { close(); engine.retry(item.key) })
            }
            if (item.state == OfflineState.Complete) {
                OverflowItem(
                    "keep", Icons.Outlined.PushPin, "Keep through cleanup", accent,
                    onClick = { close(); engine.setKeep(item.key, !item.keep) },
                    selected = item.keep,
                )
            }
            OverflowItem(
                "remove",
                if (item.state == OfflineState.Complete) Icons.Outlined.Delete else Icons.Outlined.Close,
                if (item.state == OfflineState.Complete) "Remove download" else "Cancel download",
                accent,
                onClick = { close(); scope.launch { engine.remove(item.key) } },
            )
        }
    }
}

@Composable
fun isAvailableOffline(kind: OfflineKind, mediaId: String?): Boolean =
    rememberOfflineItem(kind, mediaId)?.state == OfflineState.Complete

/** A show is marked once any of its episodes can be played offline. */
@Composable
fun showAvailableOffline(showId: String?): Boolean {
    val index = rememberOfflineIndex() ?: return false
    return showId != null && index.items.values.any { it.showId == showId && it.state == OfflineState.Complete }
}

/** A book is marked once its text or its audio is downloaded. */
@Composable
fun bookAvailableOffline(bookId: String?): Boolean =
    isAvailableOffline(OfflineKind.Book, bookId) || isAvailableOffline(OfflineKind.Audiobook, bookId)

/** For a continue-watching entry, whose TV id is the episode's. */
@Composable
fun continueAvailableOffline(type: dev.kobbler.app.data.MediaType, mediaId: String): Boolean = when (type) {
    dev.kobbler.app.data.MediaType.Movie -> isAvailableOffline(OfflineKind.Movie, mediaId)
    dev.kobbler.app.data.MediaType.TvShow, dev.kobbler.app.data.MediaType.SportsEvent -> isAvailableOffline(OfflineKind.Episode, mediaId)
    dev.kobbler.app.data.MediaType.Book -> isAvailableOffline(OfflineKind.Book, mediaId)
    dev.kobbler.app.data.MediaType.Audiobook -> isAvailableOffline(OfflineKind.Audiobook, mediaId)
}

/** For a recently-added entry, whose TV id is the show's. */
@Composable
fun recentAvailableOffline(itemType: String, id: String): Boolean = when (itemType) {
    "movie" -> isAvailableOffline(OfflineKind.Movie, id)
    "episode", "event", "tv" -> showAvailableOffline(id)
    "book", "audiobook" -> bookAvailableOffline(id)
    else -> false
}

/** Bytes on disk and bytes free, measured off the main thread whenever [key] changes. */
@Composable
fun rememberOfflineStorage(key: Any?): Pair<Long, Long> {
    val engine = LocalOfflineEngine.current ?: return 0L to 0L
    return androidx.compose.runtime.produceState(0L to 0L, key) {
        value = kotlinx.coroutines.withContext(dev.kobbler.app.platform.ioDispatcher) {
            engine.store.usedBytes() to engine.store.freeBytes()
        }
    }.value
}
