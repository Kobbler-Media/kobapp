package dev.kobbler.app.ui.navigation

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerState
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun PhoneDrawer(
    drawerState: DrawerState,
    gesturesEnabled: Boolean,
    drawerContent: @Composable ColumnScope.() -> Unit,
    content: @Composable () -> Unit,
) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = gesturesEnabled,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = Color.Transparent,
                drawerShape = RoundedCornerShape(0.dp),
                drawerTonalElevation = 0.dp,
                windowInsets = WindowInsets(0, 0, 0, 0),
                content = drawerContent,
            )
        },
        content = content,
    )
    // Composed after the root's tab-pop handler so it outranks it; unclaimed, BACK on Home
    // finished the Activity with the drawer still open.
    val scope = rememberCoroutineScope()
    InterceptBack(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
}
