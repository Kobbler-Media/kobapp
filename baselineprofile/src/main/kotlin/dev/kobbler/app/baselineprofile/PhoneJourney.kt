package dev.kobbler.app.baselineprofile

import android.content.pm.PackageManager
import android.os.SystemClock
import java.util.regex.Pattern
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

private const val SETTLE_MS = 1_200L
private const val SWIPE_STEPS = 12
private const val MENU = "Menu"
private const val CLOSE_MINI_PLAYER = "Close"
private const val RECENTLY_ADDED = "Recently Added"
private val BOTTOM_TABS = listOf("Movies", "TV Shows", "Audiobooks", "Calendar")
// Drawer-only on a phone: the bottom bar holds the first five tabs.
private val DRAWER_TABS = listOf("Live TV", "IPTV", "Books", "Downloads", "Activity")

internal val isTv: Boolean
    get() = InstrumentationRegistry.getInstrumentation().targetContext.packageManager
        .hasSystemFeature(PackageManager.FEATURE_LEANBACK)

internal fun MacrobenchmarkScope.phoneScrollFeed(flings: Int = 3) {
    val w = device.displayWidth
    val h = device.displayHeight
    repeat(flings) { device.swipe(w / 2, h * 3 / 4, w / 2, h / 4, SWIPE_STEPS); SystemClock.sleep(SETTLE_MS / 2) }
    repeat(flings) { device.swipe(w / 2, h / 4, w / 2, h * 3 / 4, SWIPE_STEPS); SystemClock.sleep(SETTLE_MS / 2) }
}

private fun MacrobenchmarkScope.phoneScrollRail() {
    val w = device.displayWidth
    val y = device.displayHeight * 2 / 3
    repeat(2) { device.swipe(w * 9 / 10, y, w / 10, y, SWIPE_STEPS); SystemClock.sleep(SETTLE_MS / 2) }
    repeat(2) { device.swipe(w / 10, y, w * 9 / 10, y, SWIPE_STEPS); SystemClock.sleep(SETTLE_MS / 2) }
}

// The bottom bar recomposes as a tab commits, so a node found a moment ago can be gone by
// the click; one fresh lookup is enough to land on its replacement.
private fun MacrobenchmarkScope.tapLabel(label: String): Boolean {
    val clicked = (0..1).any {
        val target = device.findObject(By.desc(label)) ?: device.findObject(By.text(label))
            ?: return@any false
        try {
            target.click()
            true
        } catch (_: StaleObjectException) {
            device.waitForIdle(SETTLE_MS)
            false
        }
    }
    if (!clicked) return false
    device.waitForIdle(SETTLE_MS)
    SystemClock.sleep(SETTLE_MS)
    return true
}

/** The Home hero is the resume card of the first Continue Watching title; tapping it plays. */
internal fun MacrobenchmarkScope.phonePlayHero(): Boolean {
    device.click(device.displayWidth / 2, device.displayHeight * 23 / 100)
    // The top of the picture keeps clear of the start shell's Cancel and the side seek zones.
    if (!revealTransport { device.click(device.displayWidth / 2, device.displayHeight / 4) }) {
        device.pressBack()
        returnHomeFromMiniPlayer()
        skipped("the Home hero did not start playback; the player stays uncovered.")
        return false
    }
    SystemClock.sleep(PLAYBACK_SETTLE_MS)
    device.click(device.displayWidth / 2, device.displayHeight / 4)
    SystemClock.sleep(SETTLE_MS)
    device.pressBack()
    device.awaitGone(TRANSPORT_MARKER, PLAYBACK_TIMEOUT_MS)
    returnHomeFromMiniPlayer()
    return true
}

/** Back from the phone player docks it as a mini player over the title's detail page. */
private fun MacrobenchmarkScope.returnHomeFromMiniPlayer() {
    device.findObject(By.desc(CLOSE_MINI_PLAYER))?.click()
    device.waitForIdle(SETTLE_MS)
    repeat(3) {
        if (device.hasObject(By.desc(MENU))) return@repeat
        device.pressBack()
        device.waitForIdle(SETTLE_MS)
    }
    device.waitForIdle(UI_TIMEOUT_MS)
}

internal fun MacrobenchmarkScope.phoneWalkTabs() {
    BOTTOM_TABS.forEach { tab ->
        if (tapLabel(tab)) phoneScrollFeed(flings = 2)
    }
    DRAWER_TABS.forEach { tab ->
        if (tapLabel(MENU)) {
            if (!tapLabel(tab)) device.pressBack()
        }
    }
    tapLabel(HOME_TAB)
}

// Each card is labelled with its title, so the first one is the leftmost labelled node in
// the band under the header — wherever the feed happens to be scrolled.
private fun MacrobenchmarkScope.firstRecentlyAddedCard(): UiObject2? {
    device.wait(Until.hasObject(By.text(RECENTLY_ADDED)), UI_TIMEOUT_MS)
    val w = device.displayWidth
    val h = device.displayHeight
    repeat(4) {
        val header = device.findObject(By.text(RECENTLY_ADDED))
        if (header != null) {
            val top = header.visibleBounds.bottom
            device.findObjects(By.desc(Pattern.compile(".+")))
                .filter { it.visibleBounds.top in top..(top + h / 8) && it.visibleBounds.height() > h / 10 }
                .minByOrNull { it.visibleBounds.left }
                ?.let { return it }
        }
        device.swipe(w / 2, h * 7 / 10, w / 2, h * 4 / 10, SWIPE_STEPS)
        device.waitForIdle(SETTLE_MS)
    }
    return null
}

private fun MacrobenchmarkScope.awaitPortrait() {
    val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS
    while (device.displayWidth > device.displayHeight && SystemClock.uptimeMillis() < deadline) {
        SystemClock.sleep(200)
    }
    device.waitForIdle(SETTLE_MS)
}

/** Recently Added holds shows only, and unlike Continue Watching a tap there opens the detail page. */
internal fun MacrobenchmarkScope.phoneOpenShowDetail(): Boolean {
    val card = firstRecentlyAddedCard()
    if (card == null) {
        skipped("no Recently Added card on the phone Home; ui/detail stays uncovered.")
        return false
    }
    card.click()
    if (!device.awaitLabel(DETAIL_MARKER, DETAIL_TIMEOUT_MS)) {
        device.pressBack()
        skipped("no show detail screen opened on the phone; ui/detail stays uncovered.")
        return false
    }
    device.waitForIdle(UI_TIMEOUT_MS)
    device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, 1f)
    SystemClock.sleep(SETTLE_MS)
    device.pressBack()
    device.awaitGone(DETAIL_MARKER, DETAIL_TIMEOUT_MS)
    device.waitForIdle(UI_TIMEOUT_MS)
    return true
}

// The player turns the phone to landscape and auto-rotate keeps whatever the desk says, so
// pin portrait. Home is read before playback: coming back, it is still turning and rebuilding
// its rows, and steps placed against it land on a layout that is not there yet.
internal fun MacrobenchmarkScope.phoneJourney() {
    device.setOrientationNatural()
    try {
        phoneScrollFeed()
        phoneScrollRail()
        phoneOpenShowDetail()
        phonePlayHero()
        device.setOrientationNatural()
        awaitPortrait()
        phoneWalkTabs()
    } finally {
        device.unfreezeRotation()
    }
}
