package dev.kobbler.app.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class PhoneDrawerBackTest {

    private fun ComposeUiTest.drawer(initial: DrawerValue): Pair<DrawerState, BackInterceptors> {
        val state = DrawerState(initial)
        val backs = BackInterceptors()
        setContent {
            CompositionLocalProvider(LocalBackInterceptors provides backs) {
                Box(Modifier.size(412.dp, 900.dp)) {
                    PhoneDrawer(state, gesturesEnabled = true, drawerContent = { Text("Home") }) { Text("Page") }
                }
            }
        }
        waitForIdle()
        return state to backs
    }

    @Test
    fun `back closes an open drawer instead of leaving the screen`() = runComposeUiTest {
        val (state, backs) = drawer(DrawerValue.Open)

        assertEquals(true, runOnIdle { backs.consume() })
        waitForIdle()
        assertEquals(DrawerValue.Closed, state.currentValue)
    }

    @Test
    fun `back passes through when the drawer is closed`() = runComposeUiTest {
        val (_, backs) = drawer(DrawerValue.Closed)

        assertEquals(false, runOnIdle { backs.consume() })
    }
}
