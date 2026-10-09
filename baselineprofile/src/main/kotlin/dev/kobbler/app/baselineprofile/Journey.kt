package dev.kobbler.app.baselineprofile

import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice

internal const val UI_TIMEOUT_MS = 15_000L
private const val LOG_TAG = "KobBaselineProfile"

// DesktopScaffold debounces sidebar row changes by 300 ms (SidebarNavDebounceMs);
// D-pad presses closer together than that are dropped.
private const val SIDEBAR_STEP_MS = 400L
private const val GRID_STEP_MS = 250L

// Long enough for the debounce to fire and the committed screen to compose and settle,
// which is the whole point of walking the rail — a step that only moves the highlight
// profiles nothing.
private const val TAB_SETTLE_MS = 1_500L
// The rail is server-driven, so its length is not known here. Over-stepping is free:
// sidebarIdx clamps at the last row, so the extra presses re-commit nothing.
private const val MAX_SIDEBAR_ROWS = 14
// Under the debounce, so the return leg re-commits nothing on its way past.
private const val RAIL_RETURN_MS = 100L

private const val HOME_TAB = "Home"
private const val TV_SHOWS_TAB = "TV Shows"
private const val ADD_PROFILE = "Add profile"
private const val PIN_PROMPT = "Enter your PIN"
// Detail-only: the grid and carousels never render an overflow affordance.
private const val DETAIL_MARKER = "More actions"
private const val DETAIL_TIMEOUT_MS = 5_000L
// Player-only: no browse screen draws a subtitle selector.
private const val TRANSPORT_MARKER = "Subtitles"
private const val PLAYBACK_TIMEOUT_MS = 20_000L
// A session mint, a VLC open and the first frames — the stretch the profile is for.
private const val PLAYBACK_SETTLE_MS = 6_000L

/** Injected by the baseline profile plugin; the synthetic variants do not reuse the app's own id. */
internal val targetPackage: String
    get() = InstrumentationRegistry.getArguments().getString("targetAppId")
        ?: error("targetAppId missing — run through the :baselineprofile tasks, not adb directly")

// The collapsed rail renders tabs as icons, so tab labels reach the tree as content-desc
// while screen copy reaches it as text. Match either.
private fun UiDevice.hasLabel(label: String) =
    hasObject(By.text(label)) || hasObject(By.desc(label))

private fun UiDevice.awaitLabel(label: String, timeoutMs: Long = UI_TIMEOUT_MS): Boolean {
    val deadline = SystemClock.uptimeMillis() + timeoutMs
    while (SystemClock.uptimeMillis() < deadline) {
        if (hasLabel(label)) return true
        SystemClock.sleep(200)
    }
    return hasLabel(label)
}

private fun UiDevice.awaitGone(label: String, timeoutMs: Long): Boolean {
    val deadline = SystemClock.uptimeMillis() + timeoutMs
    while (SystemClock.uptimeMillis() < deadline) {
        if (!hasLabel(label)) return true
        SystemClock.sleep(200)
    }
    return !hasLabel(label)
}

internal fun MacrobenchmarkScope.coldStartToFirstFrame() {
    pressHome()
    startActivityAndWait()
    device.waitForIdle()
}

private const val PAIR_PROMPT = "Connect this device to your Kobbler server"

/**
 * The benchmark variants are *release* type, so they carry the unsuffixed
 * `dev.kobbler.app` — NOT the `dev.kobbler.app.dbg` build used day to day. Separate
 * package, separate data dir: pairing the debug app does nothing for this one.
 *
 * Pair `dev.kobbler.app` by hand once (install any release-type variant and link it).
 * AGP reinstalls over it without clearing data, so the token then survives every run.
 * Until that happens this returns false and only cold start gets profiled.
 */
internal fun MacrobenchmarkScope.reachedLibrary(): Boolean {
    if (device.awaitLabel(HOME_TAB)) return true

    if (device.hasLabel(PAIR_PROMPT)) {
        skipped("$targetPackage is not paired. Link it to the server once — pairing the .dbg build does not count.")
        return false
    }

    // Paired but multi-profile: the picker sits between the token and the library.
    if (device.hasLabel(ADD_PROFILE)) {
        device.pressKeyCode(KeyEvent.KEYCODE_DPAD_CENTER)
        if (device.awaitLabel(HOME_TAB)) return true
    }

    if (!device.hasLabel(PIN_PROMPT)) {
        skipped("no Home tab, pairing prompt, profile picker or PIN prompt on screen.")
        return false
    }

    // Supply the PIN at run time to profile the browse journey too — never commit it:
    //   ./gradlew :androidApp:generateBaselineProfile \
    //     -Pandroid.testInstrumentationRunnerArguments.profilePin=####
    val pin = InstrumentationRegistry.getArguments().getString("profilePin")
    if (pin == null) {
        skipped("profile is PIN-locked and no profilePin argument was supplied.")
        return false
    }
    pin.forEach { digit ->
        device.pressKeyCode(KeyEvent.KEYCODE_0 + Character.digit(digit, 10))
        SystemClock.sleep(150)
    }
    device.pressKeyCode(KeyEvent.KEYCODE_ENTER)
    if (device.awaitLabel(HOME_TAB)) return true
    skipped("PIN was entered but the library never appeared.")
    return false
}

/** A silent degrade to cold-start-only is indistinguishable from a crash in the output. */
private fun skipped(why: String) {
    Log.w(LOG_TAG, "Browse journey skipped — $why Profile covers cold start only.")
}

/**
 * Sidebar order is server-driven (`navConfig.sidebarTabs`), so this walks the default
 * Home → Movies → TV Shows layout rather than seeking a label; a reordered rail lands on
 * a neighbouring tab, which still exercises the grid path the profile is for.
 */
internal fun MacrobenchmarkScope.focusTvShowsTab() {
    // Content, not the rail, holds focus on entry.
    step(KeyEvent.KEYCODE_DPAD_LEFT, SIDEBAR_STEP_MS)
    repeat(2) { step(KeyEvent.KEYCODE_DPAD_DOWN, SIDEBAR_STEP_MS) }
    device.awaitLabel(TV_SHOWS_TAB)
    step(KeyEvent.KEYCODE_DPAD_RIGHT, SIDEBAR_STEP_MS)
}

/**
 * Commit every row of the rail, then walk back up.
 *
 * Only the tabs this journey actually opens end up in the profile, and a tab that is not
 * in it composes interpreted on its first visit — measured at ~100 ms of dropped frames,
 * which reads as the rail "jumping" as you move between sections. Home/Movies/TV Shows
 * were covered and the other ten were not, so walk the lot.
 */
internal fun MacrobenchmarkScope.walkEverySidebarTab() {
    step(KeyEvent.KEYCODE_DPAD_LEFT, SIDEBAR_STEP_MS)
    repeat(MAX_SIDEBAR_ROWS) { step(KeyEvent.KEYCODE_DPAD_DOWN, TAB_SETTLE_MS) }
    // Up clamps on the rail's Search row rather than the first tab, so the return leg
    // ends one step short of Home; the extra DOWN closes search and lands on it.
    repeat(MAX_SIDEBAR_ROWS) { step(KeyEvent.KEYCODE_DPAD_UP, RAIL_RETURN_MS) }
    step(KeyEvent.KEYCODE_DPAD_DOWN, TAB_SETTLE_MS)
    step(KeyEvent.KEYCODE_DPAD_RIGHT, SIDEBAR_STEP_MS)
}

internal fun MacrobenchmarkScope.scrollShowsGrid(rows: Int = 6) {
    repeat(rows) { step(KeyEvent.KEYCODE_DPAD_DOWN, GRID_STEP_MS) }
    repeat(rows) { step(KeyEvent.KEYCODE_DPAD_UP, GRID_STEP_MS) }
}

/**
 * A blind DPAD_CENTER assumes focus is sitting on a poster. It is not reliably: entry focus
 * can still be on the rail, and the screen leads with Continue Watching (whose click starts
 * playback) and an alphabet bar. So press, check for a detail-only label, undo whatever did
 * open, step down and try the next candidate.
 */
internal fun MacrobenchmarkScope.openAndCloseShowDetail(attempts: Int = 6): Boolean {
    repeat(attempts) {
        device.pressKeyCode(KeyEvent.KEYCODE_DPAD_CENTER)
        if (device.awaitLabel(DETAIL_MARKER, DETAIL_TIMEOUT_MS)) {
            device.waitForIdle(UI_TIMEOUT_MS)
            device.pressBack()
            device.awaitGone(DETAIL_MARKER, DETAIL_TIMEOUT_MS)
            device.waitForIdle(UI_TIMEOUT_MS)
            return true
        }
        // Undo whatever the press did open (player, dialog, filter) before moving on.
        device.pressBack()
        device.waitForIdle(GRID_STEP_MS)
        step(KeyEvent.KEYCODE_DPAD_DOWN, GRID_STEP_MS)
    }
    skipped("no show detail screen opened in $attempts attempts; ui/detail stays uncovered.")
    return false
}

/**
 * Open a title and let it play, then come out again.
 *
 * The player was only ever in the profile by accident: [openAndCloseShowDetail] presses
 * blind and backs out of whatever opens, so a press that landed on Continue Watching
 * profiled the playback path and a press that landed on a poster did not. Which of the
 * two happened decided whether the first play of a run composed interpreted — the exact
 * shape of "the first one is slow and the second is quicker". Walk it on purpose.
 *
 * Continue Watching leads the Home screen, so the first row is the one that starts
 * playback; PLAYBACK_SETTLE_MS is long enough for a session to be minted, VLC to open the
 * source and the transport to draw over it.
 */
internal fun MacrobenchmarkScope.playFirstContinueWatching(): Boolean {
    device.pressKeyCode(KeyEvent.KEYCODE_DPAD_CENTER)
    if (!device.awaitLabel(TRANSPORT_MARKER, PLAYBACK_TIMEOUT_MS)) {
        device.pressBack()
        device.waitForIdle(GRID_STEP_MS)
        skipped("nothing played from the first Home row; the player stays uncovered.")
        return false
    }
    SystemClock.sleep(PLAYBACK_SETTLE_MS)
    // The transport itself: the seek bar, the track menus and the position label only
    // compose once the controls are up, and on a remote that is a keypress.
    device.pressKeyCode(KeyEvent.KEYCODE_DPAD_DOWN)
    device.waitForIdle(GRID_STEP_MS)
    SystemClock.sleep(GRID_STEP_MS)
    device.pressBack()
    device.awaitGone(TRANSPORT_MARKER, PLAYBACK_TIMEOUT_MS)
    device.waitForIdle(UI_TIMEOUT_MS)
    return true
}

private fun MacrobenchmarkScope.step(keyCode: Int, settleMs: Long) {
    device.pressKeyCode(keyCode)
    device.waitForIdle(settleMs)
    SystemClock.sleep(settleMs)
}
