package dev.kobbler.app.ui.dashboard

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import dev.kobbler.app.UiTimeoutMs
import dev.kobbler.app.data.AuthState
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.LocalAuthState
import dev.kobbler.app.data.localToday
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.runUiTest
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.navigation.DesktopScaffold
import dev.kobbler.app.ui.navigation.Destination
import dev.kobbler.app.ui.navigation.NavigationState
import dev.kobbler.app.ui.navigation.defaultNavConfig
import dev.kobbler.app.ui.player.GlobalPlayerState
import dev.kobbler.app.ui.player.StubServer
import dev.kobbler.kobfocus.FocusScopeStore
import dev.kobbler.kobfocus.LocalFocusScopeStore
import io.ktor.client.HttpClient
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The calendar opens on today and stays there.
 *
 * Compose gives an Android TV (any `android.software.leanback` device) a pivot
 * bring-into-view by default: a focused child is scrolled to 30% down its scroller. The
 * desktop has no such default, so it is installed here as the TV has it.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalFoundationApi::class)
class CalendarLandingTest {

    private val today = localToday()

    private val tvPivot = object : BringIntoViewSpec {
        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
            val childSize = abs(size)
            val target = 0.3f * containerSize
            val fits = childSize <= containerSize && containerSize - target < childSize
            return offset - if (fits) containerSize - childSize else target
        }
    }

    private fun calendar(query: String): String {
        val from = LocalDate.parse(Regex("from=([0-9-]+)").find(query)!!.groupValues[1])
        val to = LocalDate.parse(Regex("to=([0-9-]+)").find(query)!!.groupValues[1])
        val days = generateSequence(from) { it.plus(1, DateTimeUnit.DAY) }.takeWhile { it <= to }
        return days.joinToString(",", """{"items":[""", "]}") {
            """{"id":"s$it","media_type":"tv","date":"$it","title":"Show on $it","link":"/tv/s"}"""
        }
    }

    private fun DesktopComposeUiTest.todayTop(): Float? =
        onAllNodesWithText("Show on $today").fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.top

    private fun openOnTv(beforeOpening: DesktopComposeUiTest.(NavigationState) -> Unit = {}) = runUiTest(width = 960, height = 540) {
        StubServer(mapOf("/api/v1/admin/calendar" to { ex -> calendar(ex.requestURI.query) })).use { stub ->
            val nav = NavigationState()
            setContent {
                val auth = AuthState(rememberCoroutineScope(), FormFactor.TV)
                CompositionLocalProvider(
                    LocalAuthState provides auth,
                    LocalFocusScopeStore provides FocusScopeStore(),
                    LocalBringIntoViewSpec provides tvPivot,
                ) {
                    KobappTheme(formFactor = FormFactor.TV) {
                        DesktopScaffold(
                            nav = nav,
                            navConfig = defaultNavConfig,
                            api = KobblerApi(HttpClient(), stub.url, "token"),
                            formFactor = FormFactor.TV,
                            player = null,
                            gps = GlobalPlayerState(),
                        )
                    }
                }
            }
            mainClock.advanceTimeBy(1_000)
            beforeOpening(nav)
            mainClock.autoAdvance = false
            runOnIdle { nav.navigate(Destination.Tab.Calendar) }
            val deadline = System.currentTimeMillis() + UiTimeoutMs
            while (todayTop() == null) {
                check(System.currentTimeMillis() < deadline) { "today never showed" }
                Thread.sleep(5)
                mainClock.advanceTimeByFrame()
            }
            val landed = todayTop()
            val seen = mutableListOf(landed)
            repeat(90) {
                mainClock.advanceTimeByFrame()
                seen += todayTop()
            }
            mainClock.autoAdvance = true
            assertTrue(
                onAllNodes(isFocused() and hasText("Show on $today")).fetchSemanticsNodes().isNotEmpty(),
                "today's row is not focused",
            )
            assertEquals(listOf(landed), seen.distinct(), "the list moved after it first showed today")
        }
    }

    @Test
    fun `opening on a remote lands on today once`() = openOnTv()

    @Test
    fun `coming back after moving down the list lands on today once`() = openOnTv { nav ->
        runOnIdle { nav.navigate(Destination.Tab.Calendar) }
        waitUntil(timeoutMillis = UiTimeoutMs) { todayTop() != null }
        mainClock.advanceTimeBy(1_000)
        repeat(6) {
            onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            mainClock.advanceTimeBy(300)
        }
        runOnIdle { nav.navigate(Destination.Tab.Home) }
        mainClock.advanceTimeBy(1_000)
    }
}
