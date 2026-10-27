package dev.kobbler.app.ui.reader

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.theme.LocalFormFactor
import dev.kobbler.app.ui.components.tvAutoGlow
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup
import dev.kobbler.app.ui.safeTopPadding

@Composable
internal fun ReaderTopBar(
    title: String,
    onBack: () -> Unit,
    onToc: () -> Unit,
    onSettings: () -> Unit,
    onTts: () -> Unit,
    onListenAlong: () -> Unit,
    onSearch: () -> Unit,
    onBookmark: () -> Unit,
    ttsState: TtsState,
    syncMode: SyncMode,
    hasAudiobook: Boolean,
    theme: ReaderTheme,
    entryFocus: FocusRequester,
) {
    val tv = LocalFormFactor.current == FormFactor.TV
    val dimens = KobappTheme.dimens
    Row(
        modifier = Modifier
            .focusKeyGroup("topbar")
            .fillMaxWidth()
            .background(theme.backgroundColor)
            .safeTopPadding()
            .padding(
                horizontal = if (tv) dimens.pageGutterTv else 4.dp,
                vertical = if (tv) dimens.focusBleed else 2.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A remote has its own BACK, so on a TV the bar starts at its first action instead.
        val showBack = !tv
        val firstAction = if (showBack) remember { FocusRequester() } else entryFocus
        if (showBack) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.focusKey("back", focusRequester = entryFocus).tvAutoGlow(cornerRadiusDp = 999f, scale = 1.08f),
            ) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = theme.textColor)
            }
        }
        Text(
            title,
            style = KobappTheme.type.bodySm.copy(color = theme.textColor),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        if (hasAudiobook) {
            IconButton(
                onClick = onListenAlong,
                modifier = Modifier.focusKey("listenalong", focusRequester = firstAction).tvAutoGlow(cornerRadiusDp = 999f, scale = 1.08f),
            ) {
                Icon(
                    Icons.Outlined.Headphones, "Listen Along",
                    tint = if (syncMode == SyncMode.SYNCING) theme.linkColor else theme.textColor,
                )
            }
        }
        IconButton(
            onClick = onTts,
            modifier = Modifier.focusKey("tts", focusRequester = if (hasAudiobook) remember { FocusRequester() } else firstAction).tvAutoGlow(cornerRadiusDp = 999f, scale = 1.08f),
        ) {
            val icon = when (ttsState) {
                TtsState.IDLE -> Icons.Outlined.RecordVoiceOver
                TtsState.LOADING -> Icons.Outlined.HourglassTop
                TtsState.PLAYING -> Icons.Outlined.Pause
                TtsState.PAUSED -> Icons.Outlined.PlayArrow
            }
            val tint = if (ttsState != TtsState.IDLE) theme.linkColor else theme.textColor
            Icon(icon, "Read Aloud", tint = tint)
        }
        IconButton(
            onClick = onSearch,
            modifier = Modifier.focusKey("search").tvAutoGlow(cornerRadiusDp = 999f, scale = 1.08f),
        ) {
            Icon(Icons.Outlined.Search, "Search", tint = theme.textColor)
        }
        IconButton(
            onClick = onBookmark,
            modifier = Modifier.focusKey("bookmark").tvAutoGlow(cornerRadiusDp = 999f, scale = 1.08f),
        ) {
            Icon(Icons.Outlined.BookmarkBorder, "Bookmark", tint = theme.textColor)
        }
        IconButton(
            onClick = onToc,
            modifier = Modifier.focusKey("toc").tvAutoGlow(cornerRadiusDp = 999f, scale = 1.08f),
        ) {
            Icon(@Suppress("DEPRECATION") Icons.Outlined.FormatListBulleted, "Contents", tint = theme.textColor)
        }
        IconButton(
            onClick = onSettings,
            modifier = Modifier.focusKey("settings").tvAutoGlow(cornerRadiusDp = 999f, scale = 1.08f),
        ) {
            Icon(Icons.Outlined.Settings, "Settings", tint = theme.textColor)
        }
    }
}
