package dev.kobbler.app.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.BrowseHeader
import dev.kobbler.app.ui.components.EmptyState

@Composable
fun IptvRecordingsScreen(
    api: KobblerApi,
    modifier: Modifier = Modifier,
    formFactor: FormFactor = FormFactor.Desktop,
    showHeader: Boolean = true,
) {
    val colors = KobappTheme.colors
    val dimens = KobappTheme.dimens
    val accent = colors.accIptv
    val compact = formFactor == FormFactor.Phone
    val gutter = when (formFactor) {
        FormFactor.TV -> dimens.pageGutterTv
        FormFactor.Phone -> dimens.pageGutterPhone
        else -> dimens.pageGutter
    }
    Column(modifier.fillMaxSize().background(colors.bg)) {
        if (showHeader) {
            Box(Modifier.padding(horizontal = gutter, vertical = dimens.s4)) {
                BrowseHeader(
                    title = "Recordings",
                    accent = accent,
                    countLine = "no recordings",
                    compact = compact,
                )
            }
        }
        Box(Modifier.fillMaxSize()) {
            EmptyState(
                icon = Icons.Outlined.FiberManualRecord,
                title = "No recordings",
                subtitle = "Scheduled and completed IPTV recordings will appear here once a DVR endpoint is exposed.",
            )
        }
    }
}
