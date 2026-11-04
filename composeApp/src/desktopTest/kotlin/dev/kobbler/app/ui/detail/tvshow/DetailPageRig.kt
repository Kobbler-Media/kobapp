package dev.kobbler.app.ui.detail.tvshow

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.UiTimeoutMs
import dev.kobbler.app.data.AuthState
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.LocalAuthState
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.runUiTest
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.DataCache
import dev.kobbler.app.ui.navigation.DesktopScaffold
import dev.kobbler.app.ui.navigation.Destination
import dev.kobbler.app.ui.navigation.NavigationState
import dev.kobbler.app.ui.navigation.defaultNavConfig
import dev.kobbler.app.ui.player.GlobalPlayerState
import dev.kobbler.app.ui.player.StubServer
import dev.kobbler.kobfocus.FocusScopeStore
import dev.kobbler.kobfocus.LocalFocusScopeStore
import dev.kobbler.kobfocus.focusInert
import io.ktor.client.HttpClient

internal const val SHOW_PATH = "/api/v1/admin/tv/shows/show-1"

internal fun showJson(
    continueSeasonIdx: Int = 2,
    continueEpisodeIdx: Int = 1,
    position: Int = 600,
    inLibrary: (season: Int, episode: Int) -> Boolean = { _, _ -> true },
): String = buildString {
    append("""{"id":"show-1","title":"Remote Show","year":2015,"kids_allowed":false,"content_rating":"TV-MA","seasons":[""")
    append(
        (1..4).joinToString(",") { s ->
            val eps = (1..3).joinToString(",") { e ->
                val watched = s < 3
                """{"id":"e$s-$e","episode_identifier":"$e","episode_sort_order":$e,"title":"S${s}E$e title",
                   "runtime":45,"is_in_library":${inLibrary(s, e)},"watched":$watched}"""
            }
            """{"id":"season-$s","season_identifier":"$s","season_sort_order":$s,"episodes":[$eps]}"""
        },
    )
    append("""],"continue_episode":{"season_idx":$continueSeasonIdx,"episode_idx":$continueEpisodeIdx,""")
    append(""""episode_id":"e${continueSeasonIdx + 1}-${continueEpisodeIdx + 1}","position_seconds":$position}}""")
}

/** The show page driven by a remote, through the real scaffold and its rail. */
@OptIn(ExperimentalTestApi::class)
internal fun showPage(
    json: String = showJson(),
    routes: Map<String, (HttpExchange) -> String> = emptyMap(),
    canStream: Boolean = false,
    gps: GlobalPlayerState = GlobalPlayerState(),
    androidFocus: AndroidLikeFocus? = null,
    nav: NavigationState = NavigationState(),
    formFactor: FormFactor = FormFactor.TV,
    test: DesktopComposeUiTest.(StubServer) -> Unit,
) = detailPage(
    Destination.TvShowDetail("show-1"),
    mapOf<String, (HttpExchange) -> String>(SHOW_PATH to { _ -> json }) + routes,
    canStream, gps, androidFocus, nav, formFactor, test = test,
)

/** A detail page driven by a remote, through the real scaffold: it is what maps the D-pad to focus moves. */
@OptIn(ExperimentalTestApi::class)
internal fun detailPage(
    destination: Destination,
    routes: Map<String, (HttpExchange) -> String>,
    canStream: Boolean = false,
    gps: GlobalPlayerState = GlobalPlayerState(),
    androidFocus: AndroidLikeFocus? = null,
    nav: NavigationState = NavigationState(),
    formFactor: FormFactor = FormFactor.TV,
    width: Int = 960,
    height: Int = 540,
    test: DesktopComposeUiTest.(StubServer) -> Unit,
) = runUiTest(width = width, height = height) {
    DataCache.clear()
    // The page probes for streaming itself, and an unanswered probe turns it back off.
    val probe = "/api/v1/library/stats" to { _: HttpExchange -> """{"kobload_configured":$canStream}""" }
    StubServer(mapOf(probe) + routes).use { stub ->
        val api = KobblerApi(HttpClient(), stub.url, "token", canManageKids = true).also { it.canStream = canStream }
        setContent {
            val auth = AuthState(rememberCoroutineScope(), formFactor)
            // The default focus memory is process-wide; a page reopened by a later test
            // would otherwise restore focus to wherever the last one left it.
            val owner = LocalFocusManager.current
            val focusManager = remember(owner) { androidFocus?.wrap(owner) ?: owner }
            CompositionLocalProvider(
                LocalAuthState provides auth,
                LocalFocusScopeStore provides FocusScopeStore(),
                LocalFocusManager provides focusManager,
            ) {
                KobappTheme(formFactor = formFactor) {
                    // AdaptiveScaffold's covered layer; it cannot be hosted here, as it builds
                    // the real player.
                    Box(Modifier.focusInert(gps.isFullscreen || gps.loadingError != null)) {
                        DesktopScaffold(
                            nav = nav,
                            navConfig = defaultNavConfig,
                            api = api,
                            formFactor = formFactor,
                            player = null,
                            gps = gps,
                        )
                    }
                }
            }
        }
        mainClock.advanceTimeBy(1_000)
        runOnIdle { nav.navigate(destination) }
        waitUntil(timeoutMillis = UiTimeoutMs) { focusedText() != null }
        test(stub)
    }
}

/**
 * Android answers a cleared focus by having the root view ask for it straight back, and
 * Compose serves that with a search from the root, synchronously, inside the clear. The
 * desktop owner does not, so this does it by hand. [regrabs] counts the round trips.
 */
internal class AndroidLikeFocus {
    var regrabs = 0

    fun wrap(owner: FocusManager): FocusManager = object : FocusManager by owner {
        override fun clearFocus(force: Boolean) {
            owner.clearFocus(force)
            if (regrabs++ < MAX_REGRABS) owner.moveFocus(FocusDirection.Next)
        }
    }

    companion object {
        const val MAX_REGRABS = 50
    }
}

@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.press(key: Key) {
    // The last root is the topmost window: a menu or dialog, when one is up.
    onAllNodes(isRoot()).onLast().performKeyInput { pressKey(key) }
    mainClock.advanceTimeBy(400)
    waitForIdle()
}

/** Steps until [target] holds focus, waiting out each move so a slow one is not stepped past. */
@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.moveUntil(key: Key, target: (String?) -> Boolean, maxSteps: Int = 6) {
    repeat(maxSteps) {
        val from = focusedText()
        if (target(from)) return
        press(key)
        waitUntil(timeoutMillis = UiTimeoutMs) { focusedText() != from }
    }
}

@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.assertFocusSettlesOn(expected: String) {
    runCatching { waitUntil(timeoutMillis = 5_000) { focusedText() == expected } }
    kotlin.test.assertEquals(expected, focusedText())
}

@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.focusedText(): String? =
    onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Focused, true))
        .fetchSemanticsNodes().lastOrNull()
        ?.let { node ->
            node.config.getOrElseNullable(SemanticsProperties.Text) { null }?.joinToString(" ")
                ?: node.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }?.joinToString(" ")
                ?: ""
        }
