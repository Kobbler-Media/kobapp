package dev.kobbler.app.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    // dexLayoutOptimization packs every startup-profile class into the primary dex, so this
    // stays to launch → first Home frame; the browse journey below would dilute that ordering.
    @Test
    fun startup() = rule.collect(
        packageName = targetPackage,
        includeInStartupProfile = true,
    ) {
        coldStartToFirstFrame()
        reachedLibrary()
    }

    @Test
    fun journey() = rule.collect(
        packageName = targetPackage,
        includeInStartupProfile = false,
    ) {
        coldStartToFirstFrame()

        if (!reachedLibrary()) return@collect
        if (!isTv) {
            phoneJourney()
            return@collect
        }

        walkEverySidebarTab()

        // The rail walk ends back on Home content, which leads with Continue Watching.
        playFirstContinueWatching()

        focusTvShowsTab()
        scrollShowsGrid()
        openAndCloseShowDetail()
    }
}
