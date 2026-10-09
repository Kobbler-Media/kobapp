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

    @Test
    fun generate() = rule.collect(
        packageName = targetPackage,
        includeInStartupProfile = true,
    ) {
        coldStartToFirstFrame()

        if (!reachedLibrary()) return@collect

        walkEverySidebarTab()

        // The rail walk ends back on Home content, which leads with Continue Watching.
        playFirstContinueWatching()

        focusTvShowsTab()
        scrollShowsGrid()
        openAndCloseShowDetail()
    }
}
