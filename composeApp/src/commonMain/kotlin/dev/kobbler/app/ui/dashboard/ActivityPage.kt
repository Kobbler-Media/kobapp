package dev.kobbler.app.ui.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.LocalAuthState
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.SegmentedFilter
import dev.kobbler.kobfocus.focusKeyGroup

private enum class ActivityTab(val label: String) {
    History("Activity"),
    Notifications("Notifications"),
}

/**
 * What happened in the library and what the server wants the viewer to know, behind one
 * entry. A Kids profile is refused the activity feed, so it gets the inbox on its own.
 */
@Composable
fun ActivityPage(
    api: KobblerApi,
    modifier: Modifier = Modifier,
    formFactor: FormFactor = FormFactor.Desktop,
) {
    val kids = LocalAuthState.current.isKids
    var tab by remember(kids) { mutableStateOf(if (kids) ActivityTab.Notifications else ActivityTab.History) }
    val tabSwitch: (@Composable () -> Unit)? = if (kids) null else {
        {
            SegmentedFilter(
                options = ActivityTab.entries.map { it.label },
                selectedIndex = tab.ordinal,
                accent = KobappTheme.colors.accNotif,
                modifier = Modifier.focusKeyGroup("tabs"),
                onSelected = { tab = ActivityTab.entries[it] },
            )
        }
    }
    when (tab) {
        ActivityTab.History -> ActivityScreen(api, modifier, formFactor, tabSwitch)
        ActivityTab.Notifications -> NotificationsScreen(api, modifier, formFactor, tabSwitch)
    }
}
