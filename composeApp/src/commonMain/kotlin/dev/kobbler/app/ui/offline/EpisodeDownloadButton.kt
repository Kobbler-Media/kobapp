package dev.kobbler.app.ui.offline

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.Downloading
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import dev.kobbler.app.offline.OfflineItem
import dev.kobbler.app.offline.OfflineState
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.detail.OverflowItem
import dev.kobbler.app.ui.detail.OverflowMenu
import dev.kobbler.app.ui.detail.tvshow.IconBtn
import kotlinx.coroutines.launch

/** An episode row's download control: one tap starts it; once there is one, a tap asks what to do with it. */
@Composable
internal fun EpisodeDownloadButton(item: OfflineItem?, accent: Color, onDownload: () -> Unit) {
    val engine = LocalOfflineEngine.current ?: return
    val colors = KobappTheme.colors
    var open by remember { mutableStateOf(false) }
    val anchor = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    Box {
        IconBtn(
            icon = when (item?.state) {
                null -> Icons.Outlined.Download
                OfflineState.Complete -> Icons.Outlined.DownloadDone
                OfflineState.Failed -> Icons.Outlined.ErrorOutline
                else -> Icons.Outlined.Downloading
            },
            description = item?.let(::stateLabel) ?: "Download episode",
            focusKey = "download",
            tint = when (item?.state) {
                null -> colors.textLo
                OfflineState.Complete -> colors.ok
                OfflineState.Failed -> colors.warn
                else -> accent
            },
            onClick = { if (item == null) onDownload() else open = true },
            modifier = Modifier.focusRequester(anchor),
        )
        if (item != null) {
            OverflowMenu(expanded = open, anchor = anchor, onClose = { open = false }) { close ->
                if (item.state == OfflineState.Failed) {
                    OverflowItem("retry", Icons.Outlined.Refresh, "Try again", accent, onClick = { close(); engine.retry(item.key) })
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
}
