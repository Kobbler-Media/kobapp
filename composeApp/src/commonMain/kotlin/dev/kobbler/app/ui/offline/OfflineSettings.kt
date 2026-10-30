package dev.kobbler.app.ui.offline

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoDelete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.kobbler.app.offline.BackgroundDownloads
import dev.kobbler.app.offline.CleanupPolicy
import dev.kobbler.app.offline.DownloadQuality
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.ConfirmDialog
import dev.kobbler.app.ui.dashboard.SettingsDivider
import dev.kobbler.app.ui.dashboard.SettingsDropdownRow
import dev.kobbler.app.ui.dashboard.SettingsGroup
import dev.kobbler.app.ui.dashboard.SettingsRow
import dev.kobbler.app.ui.dashboard.SettingsToggleRow
import dev.kobbler.app.ui.player.formatBytes
import kotlinx.coroutines.launch

@Composable
internal fun OfflineSettings() {
    val engine = LocalOfflineEngine.current ?: return
    val index = rememberOfflineIndex() ?: return
    val scope = rememberCoroutineScope()
    var policy by remember { mutableStateOf(CleanupPolicy.current) }
    var quality by remember { mutableStateOf(DownloadQuality.current) }
    var confirmRemoveAll by remember { mutableStateOf(false) }
    var wifiOnly by remember { mutableStateOf(BackgroundDownloads.wifiOnly) }
    val (used, free) = rememberOfflineStorage(index.items.values.count { it.state == dev.kobbler.app.offline.OfflineState.Complete } to index.items.size)

    SettingsGroup(label = "Offline") {
        SettingsDropdownRow(
            icon = Icons.Outlined.HighQuality,
            title = "Download quality",
            key = "offline-quality",
            valueLabel = quality.label,
            options = DownloadQuality.entries.map { it.name to it.label },
            onSelect = { picked ->
                quality = DownloadQuality.valueOf(picked)
                DownloadQuality.current = quality
            },
        )
        SettingsDivider()
        SettingsDropdownRow(
            icon = Icons.Outlined.AutoDelete,
            title = "Downloads",
            key = "offline-cleanup",
            valueLabel = policy.label,
            options = CleanupPolicy.entries.map { it.name to it.label },
            onSelect = { picked ->
                policy = CleanupPolicy.valueOf(picked)
                CleanupPolicy.current = policy
                scope.launch { engine.maintenance.runOnce() }
            },
        )
        if (BackgroundDownloads.supported) {
            SettingsDivider()
            SettingsToggleRow(
                icon = Icons.Outlined.Wifi,
                title = "New episodes in the background on Wi-Fi only",
                key = "offline-wifi-only",
                checked = wifiOnly,
                onToggle = { wifiOnly = it; BackgroundDownloads.wifiOnly = it },
            )
        }
        SettingsDivider()
        SettingsRow(
            icon = Icons.Outlined.Storage,
            title = "Storage",
            key = "offline-storage",
            value = "${formatBytes(used)} used · ${formatBytes(free)} free",
        )
        if (index.items.isNotEmpty()) {
            SettingsDivider()
            SettingsRow(
                icon = Icons.Outlined.DeleteSweep,
                title = "Remove all downloads",
                key = "offline-remove-all",
                danger = true,
                onClick = { confirmRemoveAll = true },
            )
        }
    }
    Spacer(Modifier.height(KobappTheme.dimens.s5))

    if (confirmRemoveAll) {
        ConfirmDialog(
            title = "Remove all downloads?",
            body = "Everything kept on this device for offline use is deleted. Nothing on the server changes.",
            confirmLabel = "Remove all",
            danger = true,
            onConfirm = {
                confirmRemoveAll = false
                scope.launch {
                    index.items.keys.forEach { engine.remove(it) }
                    index.subscriptions.keys.forEach { engine.unsubscribe(it) }
                }
            },
            onDismiss = { confirmRemoveAll = false },
        )
    }
}
